-- Where the event happens, as an IANA zone such as Europe/Paris, so tickets show local time.
ALTER TABLE event ADD COLUMN time_zone text NOT NULL DEFAULT 'UTC';
ALTER TABLE event ALTER COLUMN time_zone DROP DEFAULT;

-- Who the tickets are for. Required to buy online; optional at the box office.
ALTER TABLE reservation ADD COLUMN buyer_name text;
ALTER TABLE reservation ADD COLUMN buyer_email text;
ALTER TABLE reservation ADD COLUMN buyer_phone text;
CREATE INDEX reservation_buyer_email_idx ON reservation (lower(buyer_email)) WHERE status = 'CONFIRMED';

-- Door staff: can see events and check tickets in, nothing more.
ALTER TABLE membership DROP CONSTRAINT membership_role_check;
ALTER TABLE membership ADD CONSTRAINT membership_role_check
    CHECK (role IN ('VIEWER', 'SCANNER', 'EDITOR', 'ADMIN'));

-- One per seat, and one per place of a standing area. The QR code on a ticket is its id
-- signed with the server's ticket key; the code itself is never stored.
CREATE TABLE ticket (
    id             uuid PRIMARY KEY,
    event_id       uuid           NOT NULL REFERENCES event (id),
    reservation_id uuid           NOT NULL REFERENCES reservation (id),
    unit_id        text           NOT NULL,
    -- 1 for a seat; 1 to N for N places of a standing area.
    place          integer        NOT NULL,
    label          text           NOT NULL,
    category_id    text           NOT NULL,
    category_name  text           NOT NULL,
    price          numeric(12, 2) NOT NULL,
    status         text           NOT NULL CHECK (status IN ('VALID', 'CHECKED_IN')),
    issued_at      timestamptz    NOT NULL,
    checked_in_at  timestamptz,
    checked_in_by  uuid REFERENCES app_user (id),
    UNIQUE (reservation_id, unit_id, place)
);

CREATE INDEX ticket_event_idx ON ticket (event_id);

-- Emails waiting to go out. Written in the same transaction as what they announce, and
-- sent (with retries) by a background job, so a sale never ends up without its email.
CREATE TABLE email_outbox (
    id              uuid PRIMARY KEY,
    recipient       text        NOT NULL,
    subject         text        NOT NULL,
    body            text        NOT NULL,
    -- Attach this reservation's tickets as a PDF.
    reservation_id  uuid REFERENCES reservation (id),
    status          text        NOT NULL CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    attempts        integer     NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL,
    last_error      text,
    created_at      timestamptz NOT NULL,
    sent_at         timestamptz
);

CREATE INDEX email_outbox_due_idx ON email_outbox (next_attempt_at) WHERE status = 'PENDING';
