ALTER TABLE idempotency_keys ADD COLUMN show_id UUID;

UPDATE idempotency_keys i
SET show_id = r.show_id
FROM reservations r
WHERE r.id = i.reservation_id;

ALTER TABLE idempotency_keys ALTER COLUMN show_id SET NOT NULL;
ALTER TABLE idempotency_keys DROP CONSTRAINT idempotency_keys_pkey;
ALTER TABLE idempotency_keys ADD PRIMARY KEY (user_id, show_id, key);