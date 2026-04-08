CREATE TABLE conversation_messages (
    id              BIGSERIAL  PRIMARY KEY,
    conversation_id BIGINT     NOT NULL REFERENCES conversations(id),
    role            VARCHAR(20) NOT NULL,
    content         TEXT        NOT NULL,
    created_at      TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_conv_msg_conv_id ON conversation_messages(conversation_id, created_at);
