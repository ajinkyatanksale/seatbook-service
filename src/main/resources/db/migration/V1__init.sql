CREATE TABLE shows (
  id             UUID PRIMARY KEY,
  name           TEXT   NOT NULL,
  price_paise    BIGINT NOT NULL CHECK (price_paise >= 0),
  per_user_limit INT    NOT NULL DEFAULT 4 CHECK (per_user_limit > 0),
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE reservations (
  id           UUID PRIMARY KEY,
  show_id      UUID   NOT NULL REFERENCES shows(id),
  user_id      TEXT   NOT NULL,
  seats        TEXT[] NOT NULL,
  amount_paise BIGINT NOT NULL CHECK (amount_paise >= 0),
  status       TEXT   NOT NULL CHECK (status IN ('confirmed','cancelled')),
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE seats (
  show_id        UUID NOT NULL REFERENCES shows(id),
  label          TEXT NOT NULL,
  status         TEXT NOT NULL DEFAULT 'available'
                 CHECK (status IN ('available','held','confirmed')),
  reservation_id UUID REFERENCES reservations(id),
  PRIMARY KEY (show_id, label),
  CHECK ((status = 'available') = (reservation_id IS NULL))
);

CREATE TABLE user_show_quota (
  show_id    UUID NOT NULL REFERENCES shows(id),
  user_id    TEXT NOT NULL,
  seat_count INT  NOT NULL DEFAULT 0 CHECK (seat_count >= 0),
  PRIMARY KEY (show_id, user_id)
);

CREATE TABLE idempotency_keys (
  user_id        TEXT NOT NULL,
  key            TEXT NOT NULL,
  request_hash   TEXT NOT NULL,
  reservation_id UUID NOT NULL REFERENCES reservations(id),
  PRIMARY KEY (user_id, key)
);

CREATE INDEX idx_reservations_show_user ON reservations (show_id, user_id);