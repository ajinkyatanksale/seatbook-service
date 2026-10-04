#!/usr/bin/env python3
import asyncio
import aiohttp
import json
import random
import sys
import time
from collections import Counter

if len(sys.argv) < 2:
    print("Usage: python3 burst.py <BASE_URL> [TOTAL_REQUESTS]")
    sys.exit(1)

BASE_URL = sys.argv[1].rstrip("/")
TOTAL_REQUESTS = int(sys.argv[2]) if len(sys.argv) > 2 else 20000
CONCURRENCY = 20

ADMIN_TOKEN = "admin-token"
SHOW_NAME = f"stampede-show-{int(time.time())}"
SEAT_LIST = [f"S{i:03d}" for i in range(1, 101)] # 100 seats total
PRICE_PAISE = 25000
PER_USER_LIMIT = 4

async def create_show(session):
    url = f"{BASE_URL}/shows"
    payload = {
        "name": SHOW_NAME,
        "seats": SEAT_LIST,
        "price_paise": PRICE_PAISE,
        "per_user_limit": PER_USER_LIMIT
    }
    headers = {
        "Authorization": f"Bearer {ADMIN_TOKEN}",
        "Content-Type": "application/json"
    }
    async with session.post(url, json=payload, headers=headers) as resp:
        if resp.status != 201:
            body = await resp.text()
            raise RuntimeError(f"Failed to create show: HTTP {resp.status} - {body}")
        data = await resp.json()
        return data["id"]

async def send_reservation(session, show_id, req_type, req_idx, stats):
    url = f"{BASE_URL}/shows/{show_id}/reserve"
    
    # 1. Generate realistic mixed load profiles
    if req_type == "HOT_SEAT":
        # Thousands fight over the same 3 front-row seats
        user = f"user_{req_idx % 500}"
        seats = ["S001", "S002"]
        key = f"key-hot-{req_idx}"
    elif req_type == "IDEMPOTENT_RETRY":
        # 100 repeated requests with the identical idempotency key
        user = f"retry_user_{req_idx % 5}"
        seats = [f"S{((req_idx % 10) + 10):03d}"]
        key = f"replay-key-{req_idx % 20}"
    elif req_type == "LIMIT_BUSTER":
        # User tries to book 6 seats when limit is 4
        user = f"greedy_user_{req_idx % 20}"
        seats = [f"S{(i + 30):03d}" for i in range(6)]
        key = f"limit-bust-{req_idx}"
    else:
        # General random multi-seat reservation
        user = f"random_user_{req_idx % 1000}"
        num_seats = random.randint(1, 3)
        seats = random.sample(SEAT_LIST, num_seats)
        key = f"rand-{req_idx}"

    headers = {
        "Authorization": f"Bearer user:{user}",
        "Content-Type": "application/json"
    }
    payload = {
        "seats": seats,
        "idempotency_key": key
    }

    try:
        async with session.post(url, json=payload, headers=headers, timeout=aiohttp.ClientTimeout(total=30)) as resp:
            status = resp.status
            body_text = await resp.text()
            try:
                data = json.loads(body_text)
            except Exception:
                data = {}

            stats["statuses"][status] += 1
            if status == 201:
                stats["confirmed"] += 1
            elif status == 409:
                reason = data.get("error", "unknown_409")
                stats["declined_reasons"][reason] += 1
            elif status >= 500:
                stats["5xx"] += 1
                stats["server_errors"].append((status, body_text[:120]))
            else:
                stats["other_statuses"][status] += 1
    except Exception as e:
        stats["exceptions"] += 1
        stats["server_errors"].append((0, str(e)))

async def worker(queue, session, show_id, stats):
    while True:
        task = await queue.get()
        if task is None:
            queue.task_done()
            break
        req_type, idx = task
        await send_reservation(session, show_id, req_type, idx, stats)
        queue.task_done()

async def get_show_state(session, show_id):
    url = f"{BASE_URL}/shows/{show_id}"
    headers = {"Authorization": f"Bearer {ADMIN_TOKEN}"}
    for attempt in range(5):
        async with session.get(url, headers=headers) as resp:
            if resp.status == 200:
                return await resp.json()
            await asyncio.sleep(2)
    raise RuntimeError(f"Failed to fetch show state after burst, status: {resp.status}")

async def main():
    stats = {
        "confirmed": 0,
        "declined_reasons": Counter(),
        "statuses": Counter(),
        "other_statuses": Counter(),
        "5xx": 0,
        "exceptions": 0,
        "server_errors": []
    }

    conn = aiohttp.TCPConnector(limit=CONCURRENCY, ttl_dns_cache=300)
    async with aiohttp.ClientSession(connector=conn) as session:
        print(f"[*] Target: {BASE_URL}")
        print(f"[*] Creating target show ({len(SEAT_LIST)} seats)...")
        show_id = await create_show(session)
        print(f"[*] Created show_id: {show_id}")
        print(f"[*] Launching {TOTAL_REQUESTS} requests at concurrency={CONCURRENCY}...")

        start_time = time.time()
        queue = asyncio.Queue(maxsize=1000)
        workers = [asyncio.create_task(worker(queue, session, show_id, stats)) for _ in range(CONCURRENCY)]

        # Work distribution: 40% Hot seats, 20% Retries, 15% Limit busters, 25% Random
        for i in range(TOTAL_REQUESTS):
            r = random.random()
            if r < 0.40:
                kind = "HOT_SEAT"
            elif r < 0.60:
                kind = "IDEMPOTENT_RETRY"
            elif r < 0.75:
                kind = "LIMIT_BUSTER"
            else:
                kind = "RANDOM"
            await queue.put((kind, i))

        await queue.join()
        for _ in workers:
            await queue.put(None)
        await asyncio.gather(*workers)

        duration = time.time() - start_time
        rps = TOTAL_REQUESTS / duration if duration > 0 else 0

        print(f"\n[+] Stampede completed in {duration:.2f}s ({rps:.1f} req/sec)")
        print("\n================ RESPONSE SUMMARY ================")
        print(f"Total Requests: {TOTAL_REQUESTS}")
        print(f"HTTP Statuses : {dict(stats['statuses'])}")
        print(f"201 Confirmed : {stats['confirmed']}")
        print(f"409 Declined Breakdown:")
        for reason, count in stats["declined_reasons"].items():
            print(f"   - {reason:20s}: {count}")
        print(f"5xx Server Errors: {stats['5xx']}")
        print(f"Network Failures : {stats['exceptions']}")

        print("\n================ FINAL RECONCILIATION ============")
        final_state = await get_show_state(session, show_id)
        avail = final_state.get("available", 0)
        held = final_state.get("held", 0)
        conf = final_state.get("confirmed", 0)
        total = final_state.get("total_seats", len(SEAT_LIST))

        print(f"Show Final Invariant: Available ({avail}) + Held ({held}) + Confirmed ({conf}) = {avail + held + conf} / {total}")
        
        # Validation checks
        has_5xx = stats["5xx"] > 0 or stats["exceptions"] > 0
        invariant_intact = (avail + held + conf) == total
        no_overbook = conf <= total

        if not has_5xx and invariant_intact and no_overbook:
            print("\n>>> RECONCILIATION RESULT: PASSED (ZERO 5xx, CLEAN INVARIANTS) <<<")
            sys.exit(0)
        else:
            print("\n>>> RECONCILIATION RESULT: FAILED <<<")
            if stats["server_errors"]:
                print(f"Sample error: {stats['server_errors'][0]}")
            sys.exit(1)

if __name__ == "__main__":
    asyncio.run(main())