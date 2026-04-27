-- Embedding dimension matches mxbai-embed-large (1024).
-- If you switch models, create a new migration to ALTER the column.
CREATE TABLE document_chunks (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    source_document_id  UUID        NOT NULL REFERENCES source_documents(id) ON DELETE CASCADE,
    chunk_index         INT         NOT NULL,
    content             TEXT        NOT NULL,
    embedding           vector(1024),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (source_document_id, chunk_index)
);
-- IVFFlat index for approximate nearest-neighbour cosine search.
-- lists=100 is a reasonable starting point; tune when data grows beyond ~100k rows.
CREATE INDEX document_chunks_embedding_idx
    ON document_chunks USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 100);
