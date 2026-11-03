-- People who log in: organizers' staff. Buyers check out as guests and have no account.
CREATE TABLE app_user (
    id            uuid PRIMARY KEY,
    email         text        NOT NULL,
    display_name  text        NOT NULL,
    password_hash text        NOT NULL,
    -- Consecutive failed logins; the account locks for a while after too many.
    failed_logins integer     NOT NULL DEFAULT 0,
    locked_until  timestamptz,
    created_at    timestamptz NOT NULL
);

CREATE UNIQUE INDEX app_user_email_idx ON app_user (lower(email));

-- A customer of seatmap, such as a venue operator or promoter. Owns layouts and events.
CREATE TABLE organization (
    id         uuid PRIMARY KEY,
    name       text        NOT NULL,
    created_at timestamptz NOT NULL
);

CREATE TABLE membership (
    organization_id uuid        NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    user_id         uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    role            text        NOT NULL CHECK (role IN ('VIEWER', 'EDITOR', 'ADMIN')),
    created_at      timestamptz NOT NULL,
    PRIMARY KEY (organization_id, user_id)
);

CREATE INDEX membership_user_idx ON membership (user_id);

-- Everything belongs to an organization. There were no users before this migration, so
-- existing layouts and events can't be given an owner: the tables must be empty.
ALTER TABLE layout ADD COLUMN organization_id uuid NOT NULL REFERENCES organization (id);
CREATE INDEX layout_organization_idx ON layout (organization_id);

ALTER TABLE event ADD COLUMN organization_id uuid NOT NULL REFERENCES organization (id);
CREATE INDEX event_organization_idx ON event (organization_id);

-- Where a hold came from, to cap how much one client can hold at once.
ALTER TABLE reservation ADD COLUMN client_ip text;
CREATE INDEX reservation_client_idx ON reservation (event_id, client_ip) WHERE status = 'HELD';

-- Spring Session's tables (schema-postgresql.sql from spring-session-jdbc), so logins
-- survive a restart.
CREATE TABLE SPRING_SESSION (
    PRIMARY_ID            CHAR(36) NOT NULL,
    SESSION_ID            CHAR(36) NOT NULL,
    CREATION_TIME         BIGINT   NOT NULL,
    LAST_ACCESS_TIME      BIGINT   NOT NULL,
    MAX_INACTIVE_INTERVAL INT      NOT NULL,
    EXPIRY_TIME           BIGINT   NOT NULL,
    PRINCIPAL_NAME        VARCHAR(100),
    CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
);

CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
    SESSION_PRIMARY_ID CHAR(36)     NOT NULL,
    ATTRIBUTE_NAME     VARCHAR(200) NOT NULL,
    ATTRIBUTE_BYTES    BYTEA        NOT NULL,
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID)
        REFERENCES SPRING_SESSION (PRIMARY_ID) ON DELETE CASCADE
);
