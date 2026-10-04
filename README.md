# SeatBook Service

A concurrency-safe seat reservation service built to guarantee deadlock-free execution, zero overselling, and idempotent operations under high-traffic load.

---

## Live URL

* **Production URL:** `https://seatbook-service.onrender.com`
* **Readiness Check:** `https://seatbook-service.onrender.com/readyz`
* **Liveness Check:** `https://seatbook-service.onrender.com/healthz`
* **Metrics:** `https://seatbook-service.onrender.com/metrics`

*(Note: Free-tier instances spin down after inactivity. The initial request may take 30–50 seconds to complete a cold start).*

---

## Tech Stack

* **Language & Runtime:** Java 21
* **Framework:** Spring Boot 3
* **Database:** PostgreSQL 16
* **Database Migrations:** Flyway
* **Connection Pooling:** HikariCP
* **Observability & Metrics:** Micrometer / Prometheus
* **Containerization:** Docker & Docker Compose

---

## How to Run the Application

### Prerequisites
* Docker & Docker Compose
* Python 3.10+ (for running the burst load test)

### Start via Docker Compose

Run the following command from the project root:

```bash
docker compose up --build
```

This starts:
1. A local PostgreSQL 16 database.
2. The Spring Boot application with Flyway running migrations automatically on boot.
3. The HTTP server exposed on `http://localhost:8080`.

To run in the background (detached mode):
```bash
docker compose up --build -d
```

To stop and remove containers and database volumes:
```bash
docker compose down -v
```

---

## How to Interpret Metrics

Access `/metrics` to view Prometheus-compatible telemetry.

### Key Metrics to Monitor

| Metric | Type | What It Means |
| :--- | :--- | :--- |
| `reservations_confirmed_total` | Counter | Total successful seat bookings created (`201 Created`). |
| `reservations_declined_total{reason="..."}` | Counter | Failed booking attempts partitioned by reason: `seat_taken`, `per_user_limit`, or `idempotent_replay`. |
| `seats_available{show_id="..."}` | Gauge | Number of bookable seats remaining for a given show. |
| `http_server_requests_seconds_bucket` | Histogram | Request latency distribution across endpoints (`/shows`, `/reserve`, `/readyz`). |
| `hikaricp_connections_pending` | Gauge | Threads currently waiting to acquire a DB connection. Spikes during bursts, should drop to `0`. |
| `hikaricp_connections_timeout_total` | Counter | Failed connection acquisitions. **Must be 0**; values > 0 indicate pool starvation. |

### Reconciliation Check
The metrics must align with the database state at all times:
* `seats_available` + Total confirmed seats across all bookings for a show must equal the show's initial seat count.
* `reservations_confirmed_total` + `reservations_declined_total` must equal the total number of processed reserve requests.

---

## Running the Burst Script

The load runner (`burst/burst_script.py`) executes an asynchronous mixed workload against the reservation endpoint (hot seats, duplicate idempotency keys, limit-busters, and random selections) and reconciles final seat counts against database invariants.

### 1. Install Dependencies
```bash
python -m pip install aiohttp
```

### 2. Run Against Local Environment
```bash
# Using Make
make burst BASE_URL="http://localhost:8080" REQUESTS=1000

# Or directly with Python
python burst/burst_script.py http://localhost:8080 1000
```

### 3. Run Against Live Production (Render)
```bash
# Using Make
make burst BASE_URL="[https://seatbook-service.onrender.com](https://seatbook-service.onrender.com)" REQUESTS=20000

# Or directly with Python
python burst/burst_script.py [https://seatbook-service.onrender.com](https://seatbook-service.onrender.com) 20000
```

### Expected Output
A successful run finishes with zero `5xx` errors and a passing reconciliation:
```text
================ RESPONSE SUMMARY ================
Total Requests: 20000
HTTP Statuses : {409: 18339, 201: 1661}
201 Confirmed : 1661
409 Declined Breakdown:
   - per_user_limit      : 2990
   - seat_taken          : 15349
5xx Server Errors: 0
Network Failures : 0

================ FINAL RECONCILIATION ============
Show Final Invariant: Available (0) + Held (0) + Confirmed (100) = 100 / 100

>>> RECONCILIATION RESULT: PASSED (ZERO 5xx, CLEAN INVARIANTS) <<<
```