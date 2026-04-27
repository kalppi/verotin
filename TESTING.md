# Verotin Testing Guide

## Test Structure

The project uses:
- **Unit Tests**: Pure function logic with mocked dependencies (MockK)
- **Integration Tests**: Full Spring context with Testcontainers PostgreSQL
- **Test Framework**: JUnit Jupiter (JUnit 5)

## Running Tests

### All Tests

```bash
gradle test
```

### Specific Test Class

```bash
gradle test --tests ChunkingServiceTest
gradle test --tests ExtractionServiceTest
gradle test --tests RetrieverServiceTest
gradle test --tests DeductionClassificationServiceTest
gradle test --tests IngestPipelineIntegrationTest
```

### Specific Test Method

```bash
gradle test --tests ChunkingServiceTest.empty_text_produces_no_chunks
```

### Watch Mode (Continuous Testing)

```bash
gradle test --continuous
```

### Generate Coverage Report

```bash
gradle test jacocoTestReport
# Report: build/reports/jacoco/test/html/index.html
```

## Test Categories

### Unit Tests: ChunkingService

File: `src/test/kotlin/fi/verotin/unit/ChunkingServiceTest.kt`

Tests the sliding-window text chunking algorithm:
- Empty text handling
- Single chunk for short text
- Multiple chunks for long text with overlap
- Whitespace trimming
- Overlap correctness

**Run:**
```bash
gradle test --tests ChunkingServiceTest
```

### Unit Tests: ExtractionService

File: `src/test/kotlin/fi/verotin/unit/ExtractionServiceTest.kt`

Tests LLM-based invoice extraction:
- Valid JSON response parsing
- Null field handling
- Raw response storage for auditability
- LLM failure gracefully returns minimal record
- Date parsing (YYYY-MM-DD)
- Malformed JSON handling
- Vendor name trimming

**Run:**
```bash
gradle test --tests ExtractionServiceTest
```

### Unit Tests: RetrieverService

File: `src/test/kotlin/fi/verotin/unit/RetrieverServiceTest.kt`

Tests semantic search and retrieval:
- Query embedding and similarity ranking
- Top-K limit enforcement
- Graceful handling of chunks without embeddings
- Tax-rule retrieval
- Embedding failure recovery (empty results, not crash)
- Cosine similarity correctness

**Run:**
```bash
gradle test --tests RetrieverServiceTest
```

### Unit Tests: DeductionClassificationService

File: `src/test/kotlin/fi/verotin/unit/DeductionClassificationServiceTest.kt`

Tests LLM-based deduction candidate classification:
- Extraction of candidates from LLM JSON response
- Persistence of candidates to database
- LLM failure return (empty candidates list, not crash)
- JSON parsing with partial confidence scores
- Confidence edge cases (clamping to 0.0-1.0)
- Malformed JSON graceful handling

**Run:**
```bash
gradle test --tests DeductionClassificationServiceTest
```

### Integration Tests: IngestPipelineIntegrationTest

File: `src/test/kotlin/fi/verotin/integration/IngestPipelineIntegrationTest.kt`

Full end-to-end pipeline tests (requires Testcontainers PostgreSQL):
- Full ingest of a text invoice (store → chunk → embed)
- Duplicate document detection
- Chunking with proper window/overlap
- Empty document handling
- Invoice extraction without crashing if Ollama unavailable

**Run:**
```bash
gradle test --tests IngestPipelineIntegrationTest
```

## Test Coverage Goals

| Component | Target | Status |
| --- | --- | --- |
| ChunkingService | 95%+ | ✓ |
| ExtractionService | 85%+ | ✓ |
| RetrieverService | 85%+ | ✓ |
| DeductionClassificationService | 80%+ | ✓ |
| Repositories | 70%+ | ◐ Partial (mocking DB) |
| Controllers | 50%+ | ◐ Partial (integration only) |

## Testing Best Practices for This Project

### 1. Pure Function Testing

Prefer testing pure functions over complex methods with many dependencies.

**Good:**
```kotlin
// Test chunking algorithm independently
val chunks = service.slidingWindows(text)
```

**Avoid:**
```kotlin
// Testing whole ingest (too many dependencies)
val result = service.ingest(bytes, filename, type)
```

### 2. Mock External Services

Use MockK to mock Ollama, database calls:

```kotlin
every { ollamaClient.chat(any(), any(), any()) } returns llmResponse
every { repository.insert(any()) } returns Unit
```

### 3. Test Edge Cases

- Empty inputs
- Null values
- Boundary values (0, 1, -1)
- Large inputs
- Malformed data

### 4. Verify Side Effects

Check that external calls were made with expected arguments:

```kotlin
verify { repository.insert(any()) }
```

### 5. Error Scenarios

Test that failures are handled gracefully:
- Network errors → logged and recovered
- Parsing errors → fallback to defaults
- No crashes mid-pipeline

## Example: Adding a New Test

1. **Create test file:**
   ```bash
   touch src/test/kotlin/fi/verotin/unit/MyServiceTest.kt
   ```

2. **Write test:**
   ```kotlin
   @DisplayName("MyService Tests")
   class MyServiceTest {
       private lateinit var service: MyService
       
       @BeforeEach
       fun setup() {
           service = MyService(mockk(), mockk())
       }
       
       @Test
       fun `happy path scenario`() {
           // Arrange
           val input = "test data"
           
           // Act
           val result = service.process(input)
           
           // Assert
           assertEquals("expected", result)
       }
   }
   ```

3. **Run test:**
   ```bash
   gradle test --tests MyServiceTest
   ```

## Debugging Tests

### Verbose Output

```bash
gradle test --info
```

### Single Test Execution

```bash
gradle test --tests MyServiceTest::myTestMethod -i
```

### Print Debug Info

```kotlin
println("Debug: $variable")
```

## Troubleshooting

### Tests Fail: "Ollama Connection Refused"

This is expected in CI/CD. The tests handle this gracefully.
- If testing locally, ensure Ollama is running: `ollama serve`

### Tests Fail: "PG Connection Refused"

Testcontainers should auto-provision PostgreSQL. If failing:
- Ensure Docker is running
- Clean gradle cache: `gradle clean`
- Re-run: `gradle test`

### Test Takes Too Long

- Integration tests hit real DB (Testcontainers startup) — expect 20-40s
- Run only unit tests: `gradle test --tests "*.unit.*"`

## CI/CD Integration

For GitHub Actions or similar CI:

```yaml
- name: Run tests
  run: gradle test --no-daemon

- name: Generate coverage
  run: gradle jacocoTestReport

- name: Upload coverage
  uses: codecov/codecov-action@v3
  with:
    file: ./build/reports/jacoco/test/jacocoTestReport.xml
```

## Future Enhancements

- [ ] Add contract tests for Ollama API endpoints
- [ ] Add performance benchmarks for chunking/search
- [ ] Add property-based testing (QuickCheck-style)
- [ ] Add mutation testing to ensure test quality

