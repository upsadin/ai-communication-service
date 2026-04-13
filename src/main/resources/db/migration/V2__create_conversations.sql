CREATE TABLE conversations (
    id                  BIGSERIAL    PRIMARY KEY,
    source_id           VARCHAR(255) NOT NULL UNIQUE,
    ref                 VARCHAR(100) NOT NULL,
    full_name           VARCHAR(500),
    channel_type        VARCHAR(50)  NOT NULL,
    contact_id          VARCHAR(255) NOT NULL,
    original_contact_id VARCHAR(255),
    candidate_context   TEXT,
    status              VARCHAR(50)  NOT NULL DEFAULT 'INITIATED',
    created_at          TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP    NOT NULL DEFAULT NOW()
);
