CREATE TABLE shows (
  id BIGSERIAL PRIMARY KEY,
  title VARCHAR(200) NOT NULL,
  starts_at TIMESTAMPTZ NOT NULL,
  price_per_seat NUMERIC(10,2) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE seats (
  id BIGSERIAL PRIMARY KEY,
  show_id BIGINT NOT NULL REFERENCES shows(id),
  label VARCHAR(10) NOT NULL,            -- e.g. A1
  UNIQUE (show_id, label)
);

CREATE TABLE bookings (
  id UUID PRIMARY KEY,
  show_id BIGINT NOT NULL REFERENCES shows(id),
  user_id VARCHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL,           -- PENDING, CONFIRMED, CANCELLED, EXPIRED
  hold_expires_at TIMESTAMPTZ,
  total_amount NUMERIC(10,2) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE reservations (
  id BIGSERIAL PRIMARY KEY,
  booking_id UUID NOT NULL REFERENCES bookings(id),
  show_id BIGINT NOT NULL REFERENCES shows(id),
  seat_id BIGINT NOT NULL REFERENCES seats(id),
  status VARCHAR(16) NOT NULL,           -- HELD, CONFIRMED, RELEASED
  hold_expires_at TIMESTAMPTZ
);

-- THE SAFETY NET: at most one ACTIVE reservation per (show, seat).
-- Partial index, so released/expired rows do not block re-booking.
CREATE UNIQUE INDEX uq_active_reservation
  ON reservations (show_id, seat_id)
  WHERE status IN ('HELD', 'CONFIRMED');

CREATE TABLE idempotency_keys (
  idempotency_key VARCHAR(100) NOT NULL,
  user_id VARCHAR(64) NOT NULL,
  request_hash CHAR(64) NOT NULL,        -- SHA-256 of the request body
  status VARCHAR(16) NOT NULL,           -- IN_PROGRESS, COMPLETED
  response_status INT,
  response_body JSONB,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (idempotency_key, user_id)
);
