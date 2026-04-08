CREATE TABLE deferred_tasks (
    id          BIGSERIAL    PRIMARY KEY,
    task_type   VARCHAR(50)  NOT NULL,
    payload     TEXT         NOT NULL,
    execute_at  TIMESTAMP    NOT NULL,
    status      VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    attempts    INT          NOT NULL DEFAULT 0,
    error       TEXT,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_deferred_tasks_pending ON deferred_tasks(status, execute_at)
    WHERE status = 'PENDING';
