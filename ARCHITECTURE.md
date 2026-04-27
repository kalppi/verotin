# Verotin Architecture

## Core Design Principles

1. **Local-First RAG**: All processing happens locally with Ollama, no cloud services
2. **Conservative ML**: Confidence scores reflect uncertainty, not certainty
3. **Audit Trail**: Every step stores intermediate results for traceability
4. **Explicit Over Magic**: Plain SQL, explicit error handling, no hidden framework magic
5. **Testability**: Pure functions at layer boundaries, dependencies injectable

## Layered Architecture

```
┌─────────────────────────────────────────────┐
│         REST API Layer (Controllers)         │
│  (/api/documents, /api/deductions, etc.)    │
└────────────┬────────────────────────────────┘
             │
┌────────────▼────────────────────────────────┐
│      Application Services Layer             │
│  (Ingest, Chunking, Embedding, Retrieval)   │
│  (Extraction, Classification)               │
└────────────┬────────────────────────────────┘
             │
┌────────────▼────────────────────────────────┐
│    Infrastructure Layer                     │
│  (Ollama Client, Repository)                │
│  (Database Config, Health Indicators)       │
└────────────┬────────────────────────────────┘
             │
┌────────────▼────────────────────────────────┐
│         Data Persistence Layer              │
│  (PostgreSQL, pgvector, Flyway migrations)  │
└─────────────────────────────────────────────┘
```

## Package Structure

```
fi.verotin/
  domain/                       # Domain models (DTOs, entities)
    ├── SourceDocument
    ├── DocumentChunk
    ├── Embedding
    ├── InvoiceExtraction
    ├── DeductionCandidate
    └── TaxRuleChunk
  
  repository/                   # Data access layer (explicit SQL)
    ├── SourceDocumentRepository
    ├── DocumentChunkRepository
    ├── InvoiceExtractionRepository
    ├── DeductionCandidateRepository
    └── TaxRuleChunkRepository
  
  service/                      # Business logic & orchestration
    ├── IngestService           # Import & deduplicate documents
    ├── ChunkingService         # Split text into chunks
    ├── EmbeddingService        # Generate & store vectors
    ├── ExtractionService       # Extract invoice fields via LLM
    ├── RetrieverService        # Semantic search
    └── DeductionClassificationService  # Classify candidates via LLM
  
  ollama/                       # External service integration
    ├── OllamaClient            # HTTP wrapper around Ollama API
    ├── OllamaModels            # Request/response DTOs
    └── OllamaException         # Exception type
  
  api/                          # HTTP endpoints
    ├── DocumentsController
    ├── InvoicesController
    ├── DeductionsController
    ├── RetrievalController
    └── StatsController
  
  config/                       # Configuration & setup
    ├── DatabaseConfig
    ├── WebClientConfig
    ├── OllamaProperties
    ├── AppProperties
    └── OllamaHealthIndicator
  
  fixtures/                     # Fixture data loading
    └── FixtureIngestRunner
```

## Data Flow

### 1. Import & Ingest Pipeline

```
File Input (Email, PDF, Text)
  ↓
[IngestService.ingest()]
  ├─ Compute SHA-256 hash
  ├─ Check for duplicate (dedup by hash uniqueness in DB)
  └─ If new: create SourceDocument record
  ↓
[ChunkingService.chunkDocument()]
  ├─ Split into sliding windows (1500 chars, 200 overlap)
  └─ Return list of DocumentChunk objects (no DB yet)
  ↓
[EmbeddingService.embedAndStoreDocumentChunks()]
  ├─ For each chunk:
  │  ├─ Call Ollama /api/embed (mxbai-embed-large)
  │  ├─ Catch errors → store chunk without embedding
  │  └─ Enrich chunk with FloatArray vector
  └─ Batch insert into document_chunks table
  ↓
[ExtractionService.extract()]
  ├─ Build prompt from raw_content (first 4000 chars)
  ├─ Call Ollama /api/chat with JSON mode (llama3)
  ├─ Parse response into InvoiceExtraction DTO
  └─ Store in invoice_extractions table
```

**Storage**:
- `source_documents`: Original document metadata + raw content (audit trail)
- `document_chunks`: Chunked text + embedding vectors
- `invoice_extractions`: Structured fields + raw LLM response (audit trail)

### 2. Deduction Classification Pipeline

```
Stored InvoiceExtraction
  ↓
[DeductionClassificationService.classify()]
  ├─ Build search query from vendor + line items
  ├─ [RetrieverService.retrieveTaxRules()]
  │  ├─ Embed query
  │  ├─ Find top-K tax-rule-chunks by cosine similarity
  │  └─ Return relevant text snippets
  ├─ Build system prompt (Finnish tax rules context)
  ├─ Build user prompt (extraction + tax rules + query)
  ├─ Call Ollama /api/chat with JSON mode
  ├─ Parse response into List<DeductionCandidate>
  │  └─ Each candidate has confidence (0.0-1.0) + evidence snippets
  └─ Store in deduction_candidates table
```

**Storage**:
- `deduction_candidates`: Candidates + confidence + evidence + next actions
- `tax_rule_chunks`: Foundational knowledge chunks for retrieval

### 3. Query/Search Pipeline

```
User Query: "home office equipment"
  ↓
[RetrieverService.retrieveDocumentChunks()]
  ├─ Embed query
  ├─ Execute: SELECT * FROM document_chunks
              ORDER BY embedding <=> query::vector
              LIMIT topK
  └─ Return top-K most similar chunks
```

**Index Strategy**:
- **IVFFlat** indexes on `document_chunks.embedding` and `tax_rule_chunks.embedding`
- **Cosine similarity** operator (`<=>`)
- Lists=100 for ~10k rows; tune as data grows

## Key Services

### IngestService

**Responsibility**: Pipeline orchestration

```kotlin
ingest(bytes, filename, contentType): IngestResult? {
  hash = sha256(bytes)
  if (exists(hash)) return null  // Duplicate
  
  doc = SourceDocument.create()
  saveDoc(doc)
  
  chunks = chunking.chunkDocument(doc.id, text)
  embedding.embedAndStore(chunks)
  extraction = extractor.extract(doc)
  
  return IngestResult(doc, chunks.size, extraction)
}
```

**Error Handling**: Logged and skipped; pipeline continues

### ChunkingService

**Responsibility**: Split text into semantic chunks

```kotlin
slidingWindows(text): List<String> {
  // Fixed-size windows (1500 chars)
  // with overlap (200 chars) to preserve context
  // Simple, deterministic, testable
}
```

**Rationale**: Character-based (not sentence-based) because:
- Multi-lingual support (no sentence splitter)
- Deterministic output
- Easy to test and debug

### EmbeddingService

**Responsibility**: Generate & store embedding vectors

```kotlin
embedAndStoreDocumentChunks(chunks): List<DocumentChunk> {
  val embedded = chunks.map { chunk ->
    try {
      embedding = ollama.embed(model, chunk.content)
      chunk.copy(embedding = embedding)
    } catch (ex) {
      log.warn("Failed")
      chunk  // Still store without embedding
    }
  }
  repo.insertBatch(embedded)
}
```

**Error Handling**: Chunks without embeddings still searchable via keywords

### ExtractionService

**Responsibility**: Extract structured invoice fields

```kotlin
extract(doc): InvoiceExtraction {
  systemPrompt = "You extract invoice fields..."
  userPrompt = "Extract from: ${doc.rawContent.take(4000)}"
  
  response = ollama.chat(model, msgs, jsonFormat=true)
  parsed = json.parse(response, ExtractionDto::class)
  
  extraction = InvoiceExtraction.from(parsed)
  repo.insert(extraction)
  
  return extraction
}
```

**Conservatism**: All fields nullable; LLM may not find them

### RetrieverService

**Responsibility**: Semantic search

```kotlin
retrieveDocumentChunks(query): List<String> {
  embedding = ollama.embed(model, query)
  chunks = repo.findSimilar(embedding, topK)
  return chunks.map { it.content }
}
```

**Implementation**: Cosine similarity in pgvector

### DeductionClassificationService

**Responsibility**: Classify extractions into candidates

```kotlin
classify(extraction): List<DeductionCandidate> {
  taxRules = retriever.retrieveTaxRules(query, limit=5)
  
  systemPrompt = "Classify deductions conservatively..."
  userPrompt = "Vendor: ${vendor}\nRules: ${taxRules}"
  
  response = ollama.chat(model, msgs, jsonFormat=true)
  candidates = parseResponse(response)
  
  repo.insertBatch(candidates)
  return candidates
}
```

**Critical**: Candidates marked "PENDING" require human review

## Database Schema

### source_documents

| Column | Type | Notes |
| --- | --- | --- |
| id | UUID | Primary key |
| filename | TEXT | Original filename |
| content_type | TEXT | 'email', 'pdf', 'txt' |
| raw_content | TEXT | Full text (audit trail) |
| sha256_hash | TEXT | Deduplication key (unique) |
| received_at | TIMESTAMPTZ | Import timestamp |
| created_at | TIMESTAMPTZ | Record creation time |

**Rationale**: Stores raw content for re-processing and audit

### document_chunks

| Column | Type | Notes |
| --- | --- | --- |
| id | UUID | Primary key |
| source_document_id | UUID | Foreign key |
| chunk_index | INT | 0-based position |
| content | TEXT | Chunk text |
| embedding | vector(1024) | Ollama mxbai-embed-large |
| created_at | TIMESTAMPTZ | Record creation time |
| (unique) | (source_document_id, chunk_index) | Duplicate prevention |

**Indexes**:
- `ivfflat` on `embedding` (cosine similarity search)

### invoice_extractions

| Column | Type | Notes |
| --- | --- | --- |
| id | UUID | Primary key |
| source_document_id | UUID | Foreign key (unique) |
| vendor_name | TEXT | Nullable |
| invoice_number | TEXT | Nullable |
| invoice_date | DATE | Nullable |
| total_amount | NUMERIC(14, 2) | Nullable |
| vat_amount | NUMERIC(14, 2) | Nullable |
| labor_amount | NUMERIC(14, 2) | Nullable |
| material_amount | NUMERIC(14, 2) | Nullable |
| line_items | JSONB | Array of LineItem objects |
| raw_llm_response | TEXT | Full LLM JSON (audit trail) |
| extracted_at | TIMESTAMPTZ | Extraction timestamp |

**Rationale**: Stores raw LLM response for debugging prompt quality

### deduction_candidates

| Column | Type | Notes |
| --- | --- | --- |
| id | UUID | Primary key |
| invoice_extraction_id | UUID | Foreign key |
| category | TEXT | e.g., 'tyohuonevahennys', 'tyovaline' |
| deductible_amount | NUMERIC(14, 2) | Nullable (may be uncomputable) |
| confidence | NUMERIC(4, 3) | 0.0–1.0 (uncertainty, not certainty) |
| justification | TEXT | Evidence-based explanation |
| missing_information | TEXT | What would increase/decrease confidence |
| suggested_next_action | TEXT | e.g., "Tarkista sopimus" |
| evidence_snippets | JSONB | Array of relevant text excerpts |
| tax_year | INT | 2024, 2025, etc. |
| status | TEXT | 'pending', 'accepted', 'rejected' |
| raw_llm_response | TEXT | Full LLM JSON (audit trail) |
| created_at | TIMESTAMPTZ | Creation timestamp |

**Indexes**:
- On `status` (filter pending)
- On `invoice_extraction_id` (find candidates per extraction)

### tax_rule_chunks

| Column | Type | Notes |
| --- | --- | --- |
| id | UUID | Primary key |
| rule_source | TEXT | e.g., 'TVL 2024' |
| chunk_index | INT | 0-based position |
| content | TEXT | Rule text |
| embedding | vector(1024) | Ollama mxbai-embed-large |
| created_at | TIMESTAMPTZ | Record creation time |
| (unique) | (rule_source, chunk_index) | Duplicate prevention |

**Indexes**:
- `ivfflat` on `embedding` (cosine similarity search)

## Error Handling Strategy

### External Service Failures (Ollama)

```kotlin
// Embed fails? Log warning, store without embedding
try {
    embedding = ollamaClient.embed(model, text)
} catch (ex) {
    log.warn("Embedding failed")
    embedding = null  // Proceeds
}
```

**Rationale**: Graceful degradation; keywords still search without vectors

### Database Errors

Wrapped in Spring transaction management; rolled back on failure

### Parsing Errors

```kotlin
try {
    parsed = json.readValue(response, DTO::class)
} catch (ex) {
    log.warn("JSON parse failed")
    return minimalRecord()  // Fallback
}
```

## Concurrency Model

**MVP**: Synchronous, single-threaded pipeline

- Ingest processes one document at a time
- No background job queue
- Spring JDBC provides connection pooling

**Future**: Async queue (Apache Kafka, Bull, etc.)

## Security & Validation

- **Input validation**: Null checks, length limits where needed
- **SQL injection**: Parameterized queries via JDBC
- **Authentication**: None (MVP, local-only)
- **Authorization**: None (MVP, local-only)

## Observability

### Logging

- `ChunkingService`: Log chunk counts
- `EmbeddingService`: Log embedding successes/failures
- `ExtractionService`: Log extraction by vendor name
- `Classification`: Log candidate counts by category

### Metrics (Actuator)

- `/actuator/health` → database + Ollama health
- `/api/stats` → counts by type/status

### Audit Trail

Every intermediate result stored:
- `raw_content` in SourceDocument
- `raw_llm_response` in InvoiceExtraction & DeductionCandidate
- `evidence_snippets` in DeductionCandidate

## Performance Considerations

### Chunking

- O(n) where n = document length
- No external calls

### Embedding

- ~200–500ms per chunk (Ollama inference)
- Parallelizable in future

### Search

- pgvector IVFFlat: ~O(log N) approximate NN search
- Exact search: O(N) distance computations
- Current: <100ms for ~1000 chunks

### Scaling Recommendations

| Bottleneck | Solution |
| --- | --- |
| Ollama inference | Use GPU acceleration; batch embedding |
| Database queries | Index on (status, created_at) for filtering |
| Vector search | Tune IVFFlat `lists` parameter |
| Memory | Stream large documents instead of loading all |

## Testing Strategy

- **Unit tests**: Service logic without DB
- **Integration tests**: Full pipeline with Testcontainers
- **Mock external services**: Ollama (mocked in unit tests)
- **Edge cases**: Empty inputs, null fields, malformed JSON

See `TESTING.md` for details.

## Future Enhancements

1. **Real EMAIL OAuth integration** (Gmail, Outlook)
2. **Async job queue** for bulk ingestion
3. **Fine-tuned Finnish tax LLM** for better accuracy
4. **Multi-language** support
5. **UI dashboard** for candidate review
6. **Confidence calibration** (metrics on human vs. LLM agreement)
7. **Batch PDF scanning** from folders
8. **API key authentication** for production

