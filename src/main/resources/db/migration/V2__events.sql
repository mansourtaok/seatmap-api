-- Something that sells a published layout version. Versions never change, so an event's
-- seats can't move under its buyers.
CREATE TABLE event (
    id         uuid PRIMARY KEY,
    layout_id  uuid        NOT NULL REFERENCES layout (id),
    version_id uuid        NOT NULL REFERENCES layout_version (id),
    -- The version's number, kept here so the event can be shown without a lookup.
    version_number integer NOT NULL,
    name       text        NOT NULL,
    starts_at  timestamptz NOT NULL,
    ends_at    timestamptz,
    currency   text        NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    status     text        NOT NULL CHECK (status IN ('DRAFT', 'ON_SALE', 'CLOSED', 'CANCELLED')),
    revision   integer     NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CHECK (ends_at IS NULL OR ends_at > starts_at)
);

CREATE INDEX event_layout_idx ON event (layout_id);

-- The event's price per ticket category, starting from the version's default prices.
CREATE TABLE event_price (
    event_id    uuid           NOT NULL REFERENCES event (id) ON DELETE CASCADE,
    category_id text           NOT NULL,
    price       numeric(12, 2) NOT NULL CHECK (price >= 0),
    PRIMARY KEY (event_id, category_id)
);

-- A basket of seats and standing places: held for a few minutes, then confirmed (sold)
-- or released.
CREATE TABLE reservation (
    id           uuid PRIMARY KEY,
    event_id     uuid           NOT NULL REFERENCES event (id),
    status       text           NOT NULL CHECK (status IN ('HELD', 'CONFIRMED', 'RELEASED')),
    expires_at   timestamptz    NOT NULL,
    total        numeric(12, 2) NOT NULL,
    created_at   timestamptz    NOT NULL,
    confirmed_at timestamptz,
    released_at  timestamptz
);

CREATE INDEX reservation_event_idx ON reservation (event_id);
CREATE INDEX reservation_held_expiry_idx ON reservation (expires_at) WHERE status = 'HELD';

-- What a reservation contains, at the price it was held at.
CREATE TABLE reservation_item (
    reservation_id uuid           NOT NULL REFERENCES reservation (id) ON DELETE CASCADE,
    event_id       uuid           NOT NULL REFERENCES event (id),
    unit_id        text           NOT NULL,
    quantity       integer        NOT NULL CHECK (quantity > 0),
    category_id    text           NOT NULL,
    unit_price     numeric(12, 2) NOT NULL,
    PRIMARY KEY (reservation_id, unit_id)
);

CREATE INDEX reservation_item_event_unit_idx ON reservation_item (event_id, unit_id);

-- The seats of an event that are taken. One row per seat at most, so two buyers can never
-- hold or buy the same seat; no row means the seat is free. A held row whose expires_at has
-- passed is free too, and is reclaimed by the next hold.
CREATE TABLE event_seat (
    event_id       uuid NOT NULL REFERENCES event (id),
    unit_id        text NOT NULL,
    reservation_id uuid NOT NULL REFERENCES reservation (id),
    status         text NOT NULL CHECK (status IN ('HELD', 'SOLD')),
    expires_at     timestamptz,
    PRIMARY KEY (event_id, unit_id),
    CHECK ((status = 'HELD') = (expires_at IS NOT NULL))
);

CREATE INDEX event_seat_reservation_idx ON event_seat (reservation_id);
