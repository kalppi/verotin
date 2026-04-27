CREATE TABLE deduction_candidates (
    id                      UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    invoice_extraction_id   UUID           NOT NULL REFERENCES invoice_extractions(id) ON DELETE CASCADE,
    category                TEXT           NOT NULL,  -- e.g. 'tyohuonevahennys', 'tyovaline', 'koulutus'
    deductible_amount       NUMERIC(14, 2),
    confidence              NUMERIC(4, 3)  NOT NULL CHECK (confidence BETWEEN 0 AND 1),
    justification           TEXT           NOT NULL,  -- evidence-based explanation
    missing_information     TEXT,                     -- what extra info would increase confidence
    suggested_next_action   TEXT,                     -- e.g. 'Pyydä kuitti lisätietojen vahvistamiseksi'
    evidence_snippets       JSONB          NOT NULL DEFAULT '[]',  -- ["chunk text snippet 1", ...]
    tax_year                INT,
    -- Candidates start as 'pending'; a human reviewer sets 'accepted' or 'rejected'.
    status                  TEXT           NOT NULL DEFAULT 'pending'
                                           CHECK (status IN ('pending', 'accepted', 'rejected')),
    raw_llm_response        TEXT           NOT NULL,
    created_at              TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX deduction_candidates_extraction_idx ON deduction_candidates(invoice_extraction_id);
CREATE INDEX deduction_candidates_status_idx     ON deduction_candidates(status);
COMMENT ON TABLE deduction_candidates IS
    'Possible tax deduction candidates. None of these are final tax claims.
     They require human review before any tax filing action is taken.';
