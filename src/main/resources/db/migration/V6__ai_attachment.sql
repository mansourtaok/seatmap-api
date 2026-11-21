-- Files (photos, plans) an organizer attached to an AI assistant conversation, so every
-- request of that conversation can show them to Claude without the browser re-uploading
-- them. Short-lived: a scheduled job deletes them after seatmap.ai.attachment-retention.
CREATE TABLE ai_attachment (
    id               uuid        PRIMARY KEY,
    organization_id  uuid        NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    user_id          uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    -- The designer's conversation id; only requests of that conversation may use the file.
    conversation_id  text        NOT NULL,
    file_name        text        NOT NULL,
    -- Worked out from the file's content, never from the name or the browser's claim.
    media_type       text        NOT NULL
        CHECK (media_type IN ('image/jpeg', 'image/png', 'image/gif', 'image/webp', 'application/pdf')),
    size_bytes       integer     NOT NULL,
    data             bytea       NOT NULL,
    created_at       timestamptz NOT NULL
);

CREATE INDEX ai_attachment_conversation_idx ON ai_attachment (user_id, conversation_id);
CREATE INDEX ai_attachment_created_idx ON ai_attachment (created_at);
