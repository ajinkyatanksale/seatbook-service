# seatbook

Seat reservation service: correct under load (no double-sell, per-user limits, idempotent retries).

## Run locally
    docker compose up --build
    curl localhost:8080/healthz
    curl localhost:8080/readyz


A valid body, as admin: expect 201, with snake_case keys.
No price_paise: expect 400 validation_error.
price_paise: 250.9: expect 400 (or flag if it's accepted).
Empty seats: expect 400.
A non-admin token: expect 403.

Endpoint	Success
POST /shows	201
GET /shows/{id}	200
POST /shows/{id}/reserve	201, including an idempotent replay, so a replay is indistinguishable from the first response. That's what a harness comparing the two will expect. The metrics tell us it was a replay
POST /reservations/{id}/cancel	200
