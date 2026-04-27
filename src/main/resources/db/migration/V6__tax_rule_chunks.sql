CREATE TABLE tax_rule_chunks (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    rule_source  TEXT        NOT NULL,   -- e.g. 'TVL 2024', 'OmaVero-ohje-2024'
    chunk_index  INT         NOT NULL,
    content      TEXT        NOT NULL,
    embedding    vector(1024),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (rule_source, chunk_index)
);
CREATE INDEX tax_rule_chunks_embedding_idx
    ON tax_rule_chunks USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 100);
