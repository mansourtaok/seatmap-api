-- A seat map as edited in the designer: one editable draft per layout.
CREATE TABLE layout (
    id                   uuid PRIMARY KEY,
    name                 text        NOT NULL,
    description          text,
    draft_document       jsonb       NOT NULL,
    draft_schema_version integer     NOT NULL,
    -- Incremented on every save; a save based on an older revision is rejected.
    draft_revision       integer     NOT NULL DEFAULT 0,
    created_at           timestamptz NOT NULL,
    updated_at           timestamptz NOT NULL
);

-- A published, immutable snapshot of a layout's draft. Events will point at one of these,
-- so a layout that is selling tickets can't change under them.
CREATE TABLE layout_version (
    id             uuid PRIMARY KEY,
    layout_id      uuid        NOT NULL REFERENCES layout (id),
    version_number integer     NOT NULL,
    document       jsonb       NOT NULL,
    schema_version integer     NOT NULL,
    seat_count     integer     NOT NULL,
    total_capacity integer     NOT NULL,
    published_at   timestamptz NOT NULL,
    UNIQUE (layout_id, version_number)
);

-- What can be sold in a version: every seat, plus standing areas and sections sold by
-- count. unit_id is the designer's object id, stable across versions of the same layout.
CREATE TABLE inventory_unit (
    version_id         uuid    NOT NULL REFERENCES layout_version (id) ON DELETE CASCADE,
    unit_id            text    NOT NULL,
    -- Position in the document, so seats list in the designer's order rather than by label.
    sort_order         integer NOT NULL,
    kind             text    NOT NULL CHECK (kind IN ('SEAT', 'STANDING_AREA', 'SECTION_GA')),
    capacity           integer NOT NULL CHECK (capacity >= 0),
    parent_id          text,
    parent_name        text,
    block              text,
    row_label          text,
    seat_number        text,
    label              text    NOT NULL,
    ticket_category_id text,
    design_status      text CHECK (design_status IN ('held', 'blocked')),
    x                  double precision NOT NULL,
    y                  double precision NOT NULL,
    PRIMARY KEY (version_id, unit_id)
);

-- A version's ticket categories, as defined in its document. Prices here are defaults;
-- events will set their own.
CREATE TABLE ticket_category (
    version_id    uuid          NOT NULL REFERENCES layout_version (id) ON DELETE CASCADE,
    category_id   text          NOT NULL,
    name          text          NOT NULL,
    color         text          NOT NULL,
    default_price numeric(12, 2) NOT NULL,
    PRIMARY KEY (version_id, category_id)
);

-- Floor-plan images. The document only holds their metadata (designer decision D4).
CREATE TABLE asset (
    layout_id  uuid        NOT NULL REFERENCES layout (id) ON DELETE CASCADE,
    asset_id   text        NOT NULL,
    mime_type  text        NOT NULL,
    size_bytes integer     NOT NULL,
    sha256     text        NOT NULL,
    bytes      bytea       NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (layout_id, asset_id)
);

CREATE INDEX layout_version_layout_idx ON layout_version (layout_id);
