# 🎯 START HERE

## Welcome to Verotin!

A production-minded Kotlin backend for a local-first RAG system that identifies possible Finnish tax-deduction candidates from invoice/receipt emails using local Ollama models.

**Status:** ✅ MVP Complete & Ready to Run

---

## ⏱️ 5-Minute Quick Start

### Prerequisites
```bash
# Check you have these
docker --version          # Docker & Docker Compose
java -version             # Java 21+
```

### Step 1: Start PostgreSQL (30 seconds)
```bash
docker-compose up -d
# ✓ PostgreSQL 16 with pgvector running on localhost:5432
```

### Step 2: Install & Start Ollama (in another terminal)
```bash
# Download from https://ollama.ai (or use package manager)
ollama pull mxbai-embed-large
ollama pull llama3
ollama serve
# ✓ Ollama running on localhost:11434
```

### Step 3: Run the Application (in third terminal)
```bash
SPRING_PROFILES_ACTIVE=dev gradle bootRun
# ✓ Application running on http://localhost:8080
# ✓ Fixtures loaded automatically
```

### Step 4: Try the API (in fourth terminal)
```bash
# List documents
curl http://localhost:8080/api/documents

# List deduction candidates
curl http://localhost:8080/api/deductions

# View system stats
curl http://localhost:8080/api/stats

# Search documents
curl "http://localhost:8080/api/search/documents?query=office"
```

**That's it!** 🎉 You now have a working RAG system scanning invoices and identifying tax deductions.

---

## 📚 Documentation

**For different needs, pick one:**

| I want to… | Read this |
| --- | --- |
| 📋 Quick command reference | [QUICK_REFERENCE.md](./QUICK_REFERENCE.md) |
| 🚀 Full local setup guide | [README.md](./README.md) |
| 🏗️ Understand the architecture | [ARCHITECTURE.md](./ARCHITECTURE.md) |
| 🧪 Write or run tests | [TESTING.md](./TESTING.md) |
| 🚢 Deploy to production | [DEPLOYMENT.md](./DEPLOYMENT.md) |
| ✅ See what's implemented | [PROJECT_STATUS.md](./PROJECT_STATUS.md) |
| 📂 Navigate the project | [INDEX.md](./INDEX.md) |

---

## 💡 How It Works (30-second version)

```
✉️ Email Invoice
    ↓
📝 Extract Text
    ↓
🔗 Split into Chunks
    ↓
🧠 Generate Embeddings (Ollama)
    ↓
💾 Store in PostgreSQL
    ↓
🔍 Extract Invoice Fields (LLM)
    ↓
📚 Search Relevant Tax Rules
    ↓
🤖 Classify Candidates (LLM)
    ↓
👤 ⬅️ HUMAN REVIEW REQUIRED
  (All marked "PENDING" → must accept/reject)
```

**Key:** System identifies _possible_ candidates. Humans decide.

---

## 🔧 What's Inside

- ✅ **35 Kotlin source files** — Clean, testable code
- ✅ **6 Database migrations** — PostgreSQL + pgvector schema
- ✅ **27 test cases** — Unit + integration tests
- ✅ **20+ REST APIs** — Document, extraction, deduction endpoints
- ✅ **7 documentation files** — Setup, architecture, deployment
- ✅ **2 sample invoices** — Ready to ingest
- ✅ **Comprehensive tax rules** — Finnish 2024 guidance

---

## 🎯 Common Tasks

### Add a New Invoice to Process
1. Save email as `.eml` file to `src/main/resources/fixtures/`
2. Restart with `SPRING_PROFILES_ACTIVE=dev gradle bootRun`
3. Check `/api/documents` and `/api/deductions`

### View Pending Candidates
```bash
curl http://localhost:8080/api/deductions/pending | jq '.'
```

### Accept a Candidate
```bash
CANDIDATE_ID="<id from above>"
curl -X POST http://localhost:8080/api/deductions/$CANDIDATE_ID/accept
```

### Run Tests
```bash
gradle test
```

### View Database Directly
```bash
docker exec -it verotin-postgres psql -U verotin -d verotin
# SELECT * FROM deduction_candidates WHERE status = 'pending';
```

---

## ⚠️ Important: Safety & Liability

**This system does NOT provide final tax advice.**

✂️ **Key Rule:**
- System says: "Possible deduction candidate"
- System does NOT say: "This is deductible"
- All candidates marked: ⏳ **PENDING** (require human review)
- Confidence score: Reflects uncertainty, NOT legal certainty
- Final decision: Always human (tax professional)

**Before tax filing:**
- Review all candidates with a qualified accountant
- Verify evidence against actual receipts
- Check current tax regulations
- Assume full legal responsibility yourself

---

## 🆘 Need Help?

| Problem | Solution |
| --- | --- |
| "Connection refused to PostgreSQL" | Run `docker-compose up -d` |
| "Ollama not responding" | Run `ollama serve` in another terminal |
| "Port 8080 already in use" | Kill other process or use `SERVER_PORT=8081` |
| "Don't understand the API" | See [README.md - REST API](./README.md#rest-api) |
| "Want to debug" | See [QUICK_REFERENCE.md - Troubleshooting](./QUICK_REFERENCE.md#-troubleshooting) |
| "Need to deploy to prod" | See [DEPLOYMENT.md](./DEPLOYMENT.md) |

---

## 🚀 Ready to Go Deeper?

**After quick start, read:**
1. [README.md](./README.md) — Full guide
2. [ARCHITECTURE.md](./ARCHITECTURE.md) — System design
3. [TESTING.md](./TESTING.md) — How to add tests

**Want to deploy?**
- See [DEPLOYMENT.md](./DEPLOYMENT.md)

**Questions about what's implemented?**
- See [PROJECT_STATUS.md](./PROJECT_STATUS.md) or [DELIVERY_CHECKLIST.md](./DELIVERY_CHECKLIST.md)

**Lost?**
- See [INDEX.md](./INDEX.md) for full navigation

---

## 📊 At a Glance

| Aspect | Status |
| --- | --- |
| Backend API | ✅ Complete (20+ endpoints) |
| Database | ✅ Complete (6 tables, pgvector) |
| Tests | ✅ Complete (27 test cases) |
| Documentation | ✅ Complete (67 KB) |
| Local setup | ✅ Complete (docker-compose) |
| Fixtures | ✅ Complete (2 invoices, tax rules) |
| Error handling | ✅ Comprehensive |
| Security baseline | ✅ Ready for MVP |
| Production deployment | ✅ Guide included |

---

## 🎁 What You Get

✅ Working backend (run `gradle bootRun`)  
✅ Sample data (pre-loaded invoices)  
✅ REST API (explore via curl)  
✅ Full tests (run `gradle test`)  
✅ Deployment guide (see DEPLOYMENT.md)  
✅ Clear code (35 Kotlin files, well-commented)  

**No UI** (REST API only — integrate with your frontend)  
**No auth** (local dev only)  
**No prod ops** (guidelines provided)  

---

## 🏃 Let's Go!

```bash
# 1. Open 4 terminals

# Terminal 1: Database
docker-compose up -d

# Terminal 2: Ollama
ollama serve

# Terminal 3: Application
SPRING_PROFILES_ACTIVE=dev gradle bootRun

# Terminal 4: Explore
curl http://localhost:8080/api/deductions/pending
```

**That's it!** Welcome to Verotin. 🎉

---

## 📖 Full Documentation Index

| File | Purpose | Size |
| --- | --- | --- |
| [README.md](./README.md) | Setup & API guide | 10.7 KB |
| [ARCHITECTURE.md](./ARCHITECTURE.md) | System design & schema | 15.7 KB |
| [TESTING.md](./TESTING.md) | Test guide & examples | 6.8 KB |
| [DEPLOYMENT.md](./DEPLOYMENT.md) | Production deployment | 12.2 KB |
| [PROJECT_STATUS.md](./PROJECT_STATUS.md) | Implementation summary | 14.4 KB |
| [DELIVERY_CHECKLIST.md](./DELIVERY_CHECKLIST.md) | Completeness & metrics | 15.2 KB |
| [QUICK_REFERENCE.md](./QUICK_REFERENCE.md) | Commands & shortcuts | 7.7 KB |
| [INDEX.md](./INDEX.md) | Full project navigation | — |
| [START_HERE.md](./START_HERE.md) | This file | — |

---

**Version:** 0.0.1-SNAPSHOT  
**Status:** ✅ MVP Complete  
**Next Step:** Run `docker-compose up -d && SPRING_PROFILES_ACTIVE=dev gradle bootRun`

