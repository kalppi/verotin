# Verotin Quick Reference Guide

## 🚀 Quick Start (5 minutes)

```bash
# 1. Clone/open project
cd /path/to/verotin

# 2. Start PostgreSQL
docker-compose up -d

# 3. Start Ollama (in another terminal)
ollama serve

# 4. Pull models (if not already done)
ollama pull mxbai-embed-large
ollama pull llama3

# 5. Run with fixtures ingested
SPRING_PROFILES_ACTIVE=dev gradle bootRun

# 6. Explore API (in another terminal)
curl http://localhost:8080/api/documents
curl http://localhost:8080/api/deductions
curl http://localhost:8080/api/stats
```

## 📋 Common Tasks

### View Logs
```bash
# Dev profile (logs to console)
SPRING_PROFILES_ACTIVE=dev gradle bootRun

# Watch logs from running container
docker logs -f verotin

# Earlier logs
tail -f logs/verotin.log
```

### Run Tests
```bash
# All tests
gradle test

# Unit tests only
gradle test --tests "*.unit.*"

# Integration tests only
gradle test --tests "*.integration.*"

# Single test class
gradle test --tests ChunkingServiceTest

# Single test method
gradle test --tests ChunkingServiceTest.empty_text_produces_no_chunks

# With verbose output
gradle test --info
```

### Build for Production
```bash
# Build JAR
gradle bootJar
# Result: build/libs/verotin-0.0.1-SNAPSHOT.jar

# Build Docker image
gradle bootBuildImage

# Or manually
docker build -t verotin:latest .
```

### Database Management
```bash
# Connect to PostgreSQL
docker exec -it verotin-postgres psql -U verotin -d verotin

# Query documents
SELECT COUNT(*) FROM source_documents;

# Query chunks
SELECT COUNT(*) FROM document_chunks WHERE embedding IS NOT NULL;

# Check deductions
SELECT * FROM deduction_candidates WHERE status = 'pending' ORDER BY confidence DESC;

# Backup database
docker exec verotin-postgres pg_dump -U verotin verotin > backup.sql

# Restore from backup
cat backup.sql | docker exec -i verotin-postgres psql -U verotin verotin
```

### Stop Everything
```bash
# Stop all containers
docker-compose down

# Kill running gradle process
pkill -f "gradle bootRun"

# Stop Ollama
pkill -f "ollama serve"
```

## 🔍 API Endpoints Cheat Sheet

### Documents
- `GET /api/documents` → List all
- `GET /api/documents/{id}` → Get one
- `GET /api/documents/{id}/chunks` → Get chunks

### Extractions
- `GET /api/extractions` → List all
- `GET /api/extractions/{id}` → Get one
- `GET /api/extractions/unclassified` → Unclassified

### Deductions
- `GET /api/deductions` → List all
- `GET /api/deductions/pending` → Pending review
- `GET /api/deductions/category/{category}` → By category
- `POST /api/deductions/{id}/accept` → Mark accepted
- `POST /api/deductions/{id}/reject` → Mark rejected

### Search
- `GET /api/search/documents?query=office&topK=5` → Search docs
- `GET /api/search/tax-rules?query=deduction&topK=5` → Search rules

### System
- `GET /actuator/health` → Health check
- `GET /api/stats` → System statistics

## 📊 Example API Calls

### List all pending deduction candidates
```bash
curl http://localhost:8080/api/deductions/pending | jq '.'
```

### Search for office-related documents
```bash
curl "http://localhost:8080/api/search/documents?query=office%20equipment&topK=3" | jq '.results'
```

### Accept a deduction candidate
```bash
CANDIDATE_ID="12345..."
curl -X POST http://localhost:8080/api/deductions/$CANDIDATE_ID/accept
```

### View system statistics
```bash
curl http://localhost:8080/api/stats | jq '.deductions'
```

### Health check (Ollama + Database)
```bash
curl http://localhost:8080/actuator/health | jq '.components'
```

## 🐛 Troubleshooting

### "Connection refused" to PostgreSQL
```bash
# Check if container is running
docker ps | grep verotin-postgres

# If not, start it
docker-compose up -d

# Check logs
docker logs verotin-postgres
```

### "Ollama not responding"
```bash
# Check if Ollama is running
curl http://localhost:11434/api/tags

# If not, start it
ollama serve

# Check installed models
ollama list
```

### "Embedding failed"
```bash
# Ensure models are pulled
ollama pull mxbai-embed-large
ollama pull llama3

# Restart Ollama
pkill -f "ollama serve"
ollama serve
```

### Port 8080 already in use
```bash
# Find process using port
lsof -i :8080

# Kill it
kill -9 <PID>

# Or use different port
SERVER_PORT=8081 gradle bootRun
```

### Database migration failed
```bash
# Check Flyway status
gradle flywayInfo

# Validate migrations
gradle flywayValidate

# Clean (WARNING: deletes all data!)
gradle flywayClean

# Then re-apply
gradle bootRun
```

## 📁 Where to Add/Edit

### Add new invoice fixture
```
src/main/resources/fixtures/my_invoice.eml
```

### Update tax rules
```
src/main/resources/fixtures/tax_rules_fi_2024.txt
```

### Update configuration
```
src/main/resources/application.yml  (base)
src/main/resources/application-dev.yml  (dev profile)
src/main/resources/application-prod.yml  (prod profile)
```

### Add new API endpoint
```
src/main/kotlin/fi/verotin/api/MyController.kt
```

### Add new service logic
```
src/main/kotlin/fi/verotin/service/MyService.kt
```

### Add new test
```
src/test/kotlin/fi/verotin/unit/MyServiceTest.kt
```

### Add database migration
```
src/main/resources/db/migration/V7__my_change.sql
```

## 🎯 Performance Tuning

### Chunking parameters
Edit `application.yml`:
```yaml
# Default: 1500 chars, 200 char overlap
# Smaller = more chunks = slower embedding, finer search
# Larger = fewer chunks = faster embedding, coarser search
```

### Vector search index
In PostgreSQL, if search is slow:
```sql
-- Rebuild index
REINDEX INDEX document_chunks_embedding_idx;

-- Or recreate with different parameters
DROP INDEX document_chunks_embedding_idx;
CREATE INDEX document_chunks_embedding_idx
  ON document_chunks USING ivfflat (embedding vector_cosine_ops)
  WITH (lists = 200);
```

### Ollama inference
- Use GPU: Run Ollama on GPU-enabled machine
- Batch embeddings: Future enhancement
- Async chat: Use job queue (future enhancement)

## 📚 Documentation Files

| File | Purpose |
| --- | --- |
| **README.md** | Local setup, API examples, troubleshooting |
| **ARCHITECTURE.md** | System design, data flow, schema details |
| **TESTING.md** | Test structure, how to add tests |
| **DEPLOYMENT.md** | Production setup, Docker, K8s, security |
| **PROJECT_STATUS.md** | What's implemented, statistics, next steps |
| **QUICK_REFERENCE.md** | This file — common commands & tasks |

## 🔐 Important Security Notes

⚠️ **Development environment** (as configured):
- No authentication (local use only)
- PostgreSQL password in docker-compose.yml (change for prod)
- Ollama accessible from localhost (restrict in prod)

✅ **Before production**:
- Add API key authentication
- Use HTTPS/SSL
- Rotate all credentials
- Enable database SSL
- Review DEPLOYMENT.md security section
- Set up firewall rules

## 🔗 Useful Links

- [Ollama GitHub](https://github.com/ollama/ollama)
- [Spring Boot Docs](https://spring.io/projects/spring-boot)
- [pgvector GitHub](https://github.com/pgvector/pgvector)
- [PostgreSQL Docs](https://www.postgresql.org/docs/)
- [Kotlin Language](https://kotlinlang.org/)

## ✨ What's Special About Verotin

✅ **Local-first**: No cloud services, fully offline-capable
✅ **Conservative**: Confidence reflects uncertainty, not certainty
✅ **Auditable**: All decisions stored (raw LLM responses, evidence)
✅ **Maintainable**: Explicit SQL, clear separation of concerns
✅ **Testable**: Pure functions, mocked dependencies, 27+ tests
✅ **Production-ready**: Health checks, metrics, graceful degradation

## 📞 Support

- **Issue?** Check DEPLOYMENT.md troubleshooting section
- **Architecture question?** See ARCHITECTURE.md
- **Test help?** See TESTING.md
- **API docs?** See README.md

---

Last updated: April 27, 2026

