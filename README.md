# Verotin: Local-First RAG System for Finnish Tax Deduction Candidates

A production-minded MVP Kotlin backend for scanning invoice/receipt emails and identifying possible Finnish tax-deduction candidates using local Ollama models and semantic search with pgvector.

## Key Features

- **Local-first RAG pipeline**: Import emails → extract text → chunk → embed → semantic search
- **Invoice extraction**: Structured field extraction (vendor, date, amount, VAT, line items) via LLM
- **Finnish tax deduction classification**: Identify possible deduction candidates with confidence scores and evidence
- **Audit-friendly**: Every step stores intermediate results (raw content, LLM responses, embeddings)
- **Conservative by default**: All candidates are marked as "possible" and require human review before tax filing
- **REST API**: List documents, chunks, extractions, candidates, and search indexed documents
- **Docker Compose setup**: PostgreSQL with pgvector, ready to run locally

## Architecture

### Domain Concepts

- **SourceDocument**: A deduplicated raw imported file (email body, PDF, or text file)
- **DocumentChunk**: A sliding-window text chunk with embedded semantic vector
- **Embedding**: Vector representation from Ollama embed model
- **InvoiceExtraction**: Structured invoice fields extracted by LLM
- **DeductionCandidate**: A possible tax deduction with evidence, confidence, and missing information
- **TaxRuleChunk**: Embedded chunks from Finnish tax rule knowledge base

### Pipeline

```
1. Import fixture emails/PDFs/text files
   ↓
2. Extract text from attachments and bodies
   ↓
3. Deduplicate by SHA-256 hash
   ↓
4. Create SourceDocument records
   ↓
5. Chunk text using sliding windows
   ↓
6. Generate embeddings via Ollama (mxbai-embed-large)
   ↓
7. Store chunks and embeddings in PostgreSQL + pgvector
   ↓
8. Extract structured invoice fields via LLM (llama3)
   ↓
9. Retrieve relevant tax-rule chunks via semantic search
   ↓
10. Classify into possible deduction candidates
    ↓
11. Store candidates with evidence and confidence scores
```

### Key Architecture Decisions

- **Explicit SQL over JPA**: Full SQL queries with JDBC for clarity and control
- **Pure functions for pipeline steps**: Chunking, embedding, extraction logic is testable and side-effect-free
- **Synchronous MVP**: No job queue yet; all processing happens inline
- **Error handling**: Failures logged and skipped gracefully; chunks without embeddings still searchable via keywords
- **Conservation in ML**: LLM always adds confidence scores (0.0–1.0) reflecting uncertainty, not legal certainty
- **Audit trail**: Raw LLM responses and evidence snippets stored for traceability

## Tech Stack

- **Language**: Kotlin with Spring Boot 3.3.4
- **Database**: PostgreSQL 16 with pgvector extension
- **ORM/Query**: JDBC with explicit SQL (Spring Data JDBC not used)
- **Vector Search**: pgvector with IVFFlat indexes (cosine similarity)
- **Embedding Model**: Ollama `mxbai-embed-large` (1024-dimensional)
- **Chat Model**: Ollama `llama3` (for extraction and classification)
- **PDF Parsing**: Apache PDFBox 3.0.2
- **Email Parsing**: Jakarta Mail 2.0.1
- **Testing**: Testcontainers, JUnit Jupiter, MockK, AssertJ

## Prerequisites

- Docker and Docker Compose
- Java 21+
- Ollama running locally with `mxbai-embed-large` and `llama3` models

### Install Ollama and Pull Models

```bash
# Download Ollama from https://ollama.ai
# Or via package manager (e.g., Homebrew)
ollama pull mxbai-embed-large
ollama pull llama3

# Start Ollama server (default port 11434)
ollama serve
```

## Local Setup and Run

### 1. Start PostgreSQL with pgvector

```bash
docker-compose up -d
```

Verify:
```bash
docker exec -it verotin-postgres psql -U verotin -d verotin -c "SELECT * FROM source_documents;"
```

### 2. Build the Project

```bash
gradle build
```

### 3. Run the Application (Prod Profile)

Without ingesting fixtures:
```bash
SPRING_PROFILES_ACTIVE=default gradle bootRun
```

Or as a JAR:
```bash
gradle bootJar
java -jar build/libs/verotin-0.0.1-SNAPSHOT.jar
```

Server runs on `http://localhost:8080`

### 4. Run with Fixture Ingest (Dev Profile)

Automatically ingest sample emails and classify them on startup:
```bash
SPRING_PROFILES_ACTIVE=dev gradle bootRun
```

Watch logs:
```
[INFO] [...] Loading fixture files...
[INFO] [...] Persisted SourceDocument id=... filename=sample_invoice_1.eml
[INFO] [...] Stored 5 document chunks (5 with embeddings)
[INFO] [...] Extracted invoice from ...: vendor=TechOffice Oy, amount=253.55
[INFO] [...] Classified invoice ... into 1 deduction candidates
[INFO] [...] Fixture ingest complete
```

## REST API

### Documents

- `GET /api/documents` — List all source documents
- `GET /api/documents/{id}` — Get document by ID
- `GET /api/documents/{id}/chunks` — Get chunks for a document

### Extractions

- `GET /api/extractions` — List all invoice extractions
- `GET /api/extractions/{id}` — Get extraction by ID
- `GET /api/extractions/source/{sourceDocId}` — Get extraction for a document
- `GET /api/extractions/unclassified` — Get extractions without deduction candidates

### Deductions

- `GET /api/deductions` — List all candidates
- `GET /api/deductions/{id}` — Get candidate by ID
- `GET /api/deductions/extraction/{extractionId}` — Get candidates for an extraction
- `GET /api/deductions/pending` — Get pending candidates (awaiting review)
- `GET /api/deductions/category/{category}` — Get candidates by category
- `POST /api/deductions/{id}/accept` — Mark candidate as accepted
- `POST /api/deductions/{id}/reject` — Mark candidate as rejected

### Search / Retrieval

- `GET /api/search/documents?query=...&topK=5` — Semantic search documents
- `GET /api/search/tax-rules?query=...&topK=5` — Semantic search tax rules

### System

- `GET /actuator/health` — Health check (database, Ollama connectivity)
- `GET /api/stats` — System statistics (document count, chunk count, deduction summary)

## Example Workflows

### 1. Ingest and Classify a New Invoice

Fixture files already include samples. To add a new email:

1. Copy `.eml` file to `src/main/resources/fixtures/`
2. Re-run with dev profile:
   ```bash
   SPRING_PROFILES_ACTIVE=dev gradle bootRun
   ```

### 2. Search for Deduction Candidates

```bash
curl http://localhost:8080/api/deductions/pending
```

Response:
```json
[
  {
    "id": "...",
    "invoiceExtractionId": "...",
    "category": "tyohuonevahennys",
    "deductibleAmount": 47.99,
    "confidence": 0.72,
    "justification": "Office supplies (keyboard, lamp) are used for home office setup. ...",
    "missingInformation": "Verification of home office space percentage; employment status confirmation",
    "suggestedNextAction": "Confirm home office percentage and employment contract status",
    "evidenceSnippets": [
      "Ergonomic keyboard: €89.99",
      "Desk lamp LED: €34.50",
      "This invoice is for work-related office equipment and supplies."
    ],
    "status": "PENDING",
    "createdAt": "2024-02-15T14:30:00Z"
  }
]
```

### 3. Accept a Candidate

```bash
curl -X POST http://localhost:8080/api/deductions/{candidateId}/accept
```

### 4. Semantic Search Documents

```bash
curl "http://localhost:8080/api/search/documents?query=home%20office%20equipment&topK=3"
```

Response:
```json
{
  "query": "home office equipment",
  "topK": 3,
  "results": [
    "This invoice is for work-related office equipment and supplies.",
    "Ergonomic keyboard: €89.99 (qty: 1)"
  ]
}
```

## Testing

### Unit Tests

```bash
gradle test
```

Currently includes:
- `ChunkingServiceTest`: Sliding window chunking logic

### Integration Tests

```bash
gradle integrationTest
```

Uses Testcontainers to spin up PostgreSQL for each test suite.

### Run Specific Test

```bash
gradle test --tests ChunkingServiceTest
```

## Troubleshooting

### Ollama Connection Refused

Ensure Ollama is running:
```bash
curl http://localhost:11434/api/tags
```

If not:
```bash
ollama serve
```

### PostgreSQL Connection Failed

Check Docker container:
```bash
docker ps | grep verotin-postgres
docker logs verotin-postgres
```

Restart if needed:
```bash
docker-compose down
docker-compose up -d
```

### Model Not Found

Pull the required models:
```bash
ollama pull mxbai-embed-large
ollama pull llama3
ollama list
```

### Build Fails Due to Missing pgvector

Ensure PostgreSQL is running (Gradle test framework will use Testcontainers):
```bash
gradle build -x test
```

## Future Enhancements

- [ ] Gmail/Outlook OAuth for live email ingestion
- [ ] Async job queue for bulk processing
- [ ] Fine-tuned Finnish tax LLM
- [ ] UI dashboard for candidate review
- [ ] Tax year configuration and rule versioning
- [ ] Batch PDF invoice scanning from folders
- [ ] Multi-language support
- [ ] Confidence calibration metrics

## Important: Safety and Liability

**This system does NOT provide final tax advice.** It identifies _possible_ candidates that require human verification. All candidates are marked as "PENDING" and must be reviewed by a qualified tax professional before being used in any tax filing. The developers and maintainers assume no liability for classifications or outcomes of tax filings based on this system.

## Configuration

Edit `src/main/resources/application.yml`:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/verotin
    username: verotin
    password: verotin
  flyway:
    enabled: true
ollama:
  base-url: http://localhost:11434
  embed-model: mxbai-embed-large
  chat-model: llama3
  embedding-dimensions: 1024
```

## Development Notes

### Adding a New Domain Concept

1. Create data class in `domain/`
2. Create repository with explicit SQL in `repository/`
3. Write tests in `src/test/kotlin/`
4. Add migration in `db/migration/`
5. Wire into service layer in `service/`

### Chunking Strategy

Defaults: 1500-character windows with 200-character overlap. Adjust in `src/main/resources/application.yml` or via constructor in `ChunkingService`.

### Embedding Dimensions

Currently 1024 (mxbai-embed-large). If changing models, create new migration to alter column size and rebuild indexes.

## License

Not specified. Consult with project maintainers.

## Authors

- Verotin team


