CREATE TABLE chat_message (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id VARCHAR(128),
    dify_message_id VARCHAR(128),
    user_id VARCHAR(128) NOT NULL,
    question LONGTEXT NOT NULL,
    answer LONGTEXT NOT NULL,
    route VARCHAR(16),
    latency_ms BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    error_msg VARCHAR(512),
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT ck_chat_status CHECK (status IN ('SUCCESS', 'FAILED'))
) ${tableOptions};
CREATE INDEX ix_chat_conversation ON chat_message (conversation_id, created_at, id);

CREATE TABLE chat_citation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    chat_message_id BIGINT NOT NULL,
    document_name VARCHAR(512),
    segment_position INT,
    score DOUBLE,
    content_preview TEXT,
    CONSTRAINT fk_citation_message FOREIGN KEY (chat_message_id) REFERENCES chat_message(id)
) ${tableOptions};
CREATE INDEX ix_citation_message ON chat_citation (chat_message_id);

CREATE TABLE eval_run (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    started_at DATETIME(6) NOT NULL,
    finished_at DATETIME(6),
    total INT NOT NULL,
    route_correct INT NOT NULL,
    refusal_correct INT NOT NULL,
    avg_latency_ms DOUBLE NOT NULL,
    note TEXT
) ${tableOptions};

CREATE TABLE eval_result (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id BIGINT NOT NULL,
    question_id VARCHAR(32) NOT NULL,
    type VARCHAR(32) NOT NULL,
    question LONGTEXT NOT NULL,
    expected_route VARCHAR(16) NOT NULL,
    actual_route VARCHAR(16),
    answer LONGTEXT NOT NULL,
    reference_answer LONGTEXT NOT NULL,
    citations_json LONGTEXT NOT NULL,
    latency_ms BIGINT NOT NULL,
    auto_check VARCHAR(16) NOT NULL,
    manual_verdict VARCHAR(32),
    CONSTRAINT fk_result_run FOREIGN KEY (run_id) REFERENCES eval_run(id),
    CONSTRAINT uq_run_question UNIQUE (run_id, question_id),
    CONSTRAINT ck_auto_check CHECK (auto_check IN ('PASS', 'FAIL', 'MANUAL'))
) ${tableOptions};
