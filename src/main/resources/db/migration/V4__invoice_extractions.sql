CREATE TABLE invoice_extractions (
    id                  UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    source_document_id  UUID           NOT NULL UNIQUE REFERENCES source_documents(id) ON DELETE CASCADE,
    vendor_name         TEXT,
    invoice_number      TEXT,
    invoice_date        DATE,
    payment_date        DATE,
    total_amount        NUMERIC(14, 2),
    currency            CHAR(3),
    vat_amount          NUMERIC(14, 2),
    labor_amount        NUMERIC(14, 2),
    material_amount     NUMERIC(14, 2),
    -- Full line-items list stored as JSONB for auditability.
    -- Schema: [{"description": "...", "quantity": 1, "unit_price": 0.0, "total": 0.0}]
    line_items          JSONB          NOT NULL DEFAULT '[]',
    raw_llm_response    TEXT           NOT NULL,   -- store raw LLM output for debugging
    extracted_at        TIMESTAMPTZ    NOT NULL DEFAULT now()
);
COMMENT ON COLUMN invoice_extractions.raw_llm_response IS
    'Raw LLM JSON response, kept for auditability and prompt debugging.';
