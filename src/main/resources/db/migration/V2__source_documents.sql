CREATE TABLE source_documents (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    filename      TEXT        NOT NULL,
    content_type  TEXT        NOT NULL,   -- 'email', 'pdf', 'txt'
    raw_content   TEXT        NOT NULL,
    sha256_hash   TEXT        NOT NULL UNIQUE,
    received_at   TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE source_documents IS 'One row per unique imported document (email body or attachment).';
