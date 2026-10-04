# seatbook

Seat reservation service: correct under load (no double-sell, per-user limits, idempotent retries).

## Run locally
    docker compose up --build
    curl localhost:8080/healthz
    curl localhost:8080/readyz

