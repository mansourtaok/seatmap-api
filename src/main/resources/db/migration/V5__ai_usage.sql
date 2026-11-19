-- One row per call the AI assistant made to Claude: who asked, what it cost, how it went.
-- The base for per-plan AI allowances later (monthly sums per organization).
CREATE TABLE ai_usage (
    id                    uuid PRIMARY KEY,
    organization_id       uuid           NOT NULL REFERENCES organization (id),
    user_id               uuid           NOT NULL REFERENCES app_user (id),
    -- The designer's conversation id; groups the turns of one chat.
    conversation_id       text           NOT NULL,
    layout_id             uuid,
    model                 text           NOT NULL,
    input_tokens          integer        NOT NULL DEFAULT 0,
    output_tokens         integer        NOT NULL DEFAULT 0,
    cache_read_tokens     integer        NOT NULL DEFAULT 0,
    cache_write_tokens    integer        NOT NULL DEFAULT 0,
    -- From the configured price table at the time of the call, in US dollars.
    estimated_cost_usd    numeric(12, 6) NOT NULL DEFAULT 0,
    duration_ms           integer        NOT NULL,
    status                text           NOT NULL
        CHECK (status IN ('SUCCESS', 'NEEDS_CLARIFICATION', 'CANNOT_DO', 'REFUSED', 'INVALID_RESPONSE', 'ERROR')),
    error                 text,
    created_at            timestamptz    NOT NULL
);

CREATE INDEX ai_usage_organization_idx ON ai_usage (organization_id, created_at);
