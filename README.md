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

POST /shows as admin, 10 seats	201, snake_case JSON, available: 10, total_seats: 10
the same with a user token	403 forbidden
no price_paise	400 validation_error
price_paise: 250.9	400 (tell me if it's accepted)
seats: ["A1","A1"]	400, not 500
GET /shows/{id}	200, and available + held + confirmed == total_seats
GET /shows/not-a-uuid	400
GET /shows/<random valid uuid>	404 show_not_found
POST /shows with "user_id":"mallory" in the body	still 201
