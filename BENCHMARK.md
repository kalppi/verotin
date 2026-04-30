# Benchmark Suite

This benchmark system allows you to test and compare:
1. **Invoice Extraction** — LLM quality for extracting vendor, invoice number, total, line items
2. **Deduction Classification** — Classification service quality for identifying tax deduction candidates

Both use JSONL datasets with ground truth for scoring accuracy.

## Quick Start

### 1. Create a Benchmark Dataset

Create `src/test/resources/benchmark-fixtures.jsonl` with one JSON line per test case:

```json
{
  "text": "Invoice #2024-001\nVendor: TechCorp\nTotal: €1234.56\nItems:\n- Widget: 2 × €617.28 = €1234.56",
  "expectedVendor": "TechCorp",
  "expectedInvoiceNumber": "2024-001",
  "expectedTotal": "1234.56",
  "expectedLineItems": [
    {
      "description": "Widget",
      "quantity": 2.0,
      "unitPrice": 617.28,
      "total": 1234.56
    }
  ]
}
```

**Field Descriptions:**
- `text`: Raw invoice/receipt text to extract from
- `expectedVendor`: Expected vendor/company name
- `expectedInvoiceNumber`: Expected invoice number
- `expectedTotal`: Expected total amount (as string)
- `expectedLineItems`: Array of expected line items:
  - `description`: Product/service name
  - `quantity`: Item count
  - `unitPrice`: Price per unit
  - `total`: Total for this line

**Tips:**
- Include 5-20 diverse test cases for reliable benchmarking
- Use real documents or realistic synthetic data
- Cover edge cases: wrapped text, different table layouts, VAT variations

### 2. Run the Benchmark

```bash
./gradlew test --tests "*LlmBenchmarkTest*"
```

### 3. Review Results

The test will:
1. Extract each test case using the current LLM
2. Score each extraction against ground truth
3. Print summary metrics to console
4. Generate `benchmark-results.csv` report

Example output:

```
📊 LLM Extraction Benchmark
================================================================================
Dataset: 10 test cases

[1/10] Testing: Invoice #2024-001...
  Vendor: 100.0 | Invoice: 100.0 | Total: 100.0
  Line Items: 95.5 | Overall: 98.9%

...

======================== Summary ========================
Vendor Name:    98.5%
Invoice Number: 97.2%
Total Amount:   99.1%
Line Items:     92.3%
Overall Score:  96.8%

💾 Report exported: benchmark-results.csv
```

### 4. Interpret Metrics

| Metric | Range | Meaning |
|--------|-------|---------|
| **Vendor Name** | 0-100% | Exact or near-match accuracy |
| **Invoice Number** | 0-100% | Exact or near-match accuracy |
| **Total Amount** | 0-100% | Within ±€0.50 tolerance |
| **Line Items** | 0-100% | Avg of description match + qty match + price match |
| **Overall Score** | 0-100% | Weighted average (line items weight × 0.5) |

## Customization

### Adding More Test Cases

Append JSONL lines to `benchmark-fixtures.jsonl`:

```json
{"text":"...invoice text...","expectedVendor":"...","expectedInvoiceNumber":"...","expectedTotal":"...","expectedLineItems":[...]}
{"text":"...another invoice...","expectedVendor":"...","expectedInvoiceNumber":"...","expectedTotal":"...","expectedLineItems":[...]}
```

### Testing Different Prompts

Modify the `buildSystemPrompt()` function in `LlmBenchmarkTest.kt`:

```kotlin
private fun buildSystemPrompt(): String = """
    Your custom system prompt here...
    Be specific about column typing, VAT handling, etc.
""".trimIndent()
```

Then re-run:
```bash
./gradlew test --tests "*LlmBenchmarkTest*"
```

### Testing Different Models

To test a different model (e.g., `llama2` instead of `mistral`), modify:

```kotlin
val response = ollamaClient.chat(
    model = "llama2",  // Change here
    messages = listOf(...),
    jsonFormat = true,
)
```

## CSV Report Format

Each row: `Test Case | Vendor % | Invoice % | Total % | Line Items % | Overall %`

Example:
```csv
Test Case,Vendor %,Invoice %,Total %,Line Items %,Overall %
"Invoice #2024-001",100.0,100.0,100.0,95.5,98.9
"Lasku 2024",98.5,96.0,99.1,91.2,96.2
```

## Advanced: Comparing Multiple Prompts

Create copies of `LlmBenchmarkTest.kt` with different prompts:

```
LlmBenchmarkTestPromptA.kt  (buildSystemPrompt() variant A)
LlmBenchmarkTestPromptB.kt  (buildSystemPrompt() variant B)
LlmBenchmarkTestPromptC.kt  (buildSystemPrompt() variant C)
```

Run all:
```bash
./gradlew test --tests "*LlmBenchmarkTest*"
```

Then compare the three `benchmark-results-*.csv` files.

## Scoring Details

### String Matching (Vendor, Invoice Number)
- Exact match (case-insensitive, trimmed): **100%**
- Levenshtein similarity fallback: `1 - (edit_distance / max_length)`
- If both null: **100%**

### Decimal Matching (Total Amount)
- Within ±€0.50 tolerance: **100%**
- Outside tolerance: `1 - (difference / tolerance)`
- If both null: **100%**

### Line Item Matching
1. Match expected items to actual items using description similarity
2. For each match:
   - Description similarity: Levenshtein
   - Quantity: exact match = 100%, else 80%
   - Unit price: decimal match with ±€0.10 tolerance
   - Total: decimal match with ±€0.50 tolerance
3. Average all line items

### Overall Score
```
(vendor + invoice + total + lineItems × 0.5) / 3.5 × 100%
```

Line items weighted at 0.5 because quality extraction is more important than perfect vendor matching.

## Troubleshooting

**"No benchmark dataset found"**
- Ensure `benchmark-fixtures.jsonl` exists at `src/test/resources/`
- Check file is valid JSONL (one JSON object per line, no trailing commas)

**"Extraction failed"**
- Verify Ollama is running: `ollama serve`
- Check model name matches available model: `ollama list`
- Check LLM output is valid JSON

**Low scores on all metrics**
- Review system prompt clarity and specificity
- Check dataset vs. LLM expectations (e.g., non-English names)
- Consider simpler test cases first

---

## Deduction Classification Benchmark

This benchmark tests the `DeductionClassificationService` to measure how well it identifies tax deduction candidates.

### 1. Create Classification Dataset

Create `src/test/resources/benchmark-deductions.jsonl` with one JSON line per line item test case:

```json
{
  "id": "labor-001",
  "description": "Sähkötyö, sisältää turvakytkin asennus",
  "context": "UNKNOWN",
  "expectedCategory": "Kotitalousvähennys",
  "expectedDecision": "candidate",
  "expectedConfidenceBand": "high",
  "reasonTag": "explicit_electrical_work"
}
```

**Field Descriptions:**
- `id`: Unique test case ID
- `description`: Line item description to classify
- `context`: Additional context (usually "UNKNOWN")
- `expectedCategory`: Expected deduction type (e.g. "Kotitalousvähennys", or "REJECTED")
- `expectedDecision`: Should classifier find a candidate? ("candidate" or "rejected")
- `expectedConfidenceBand`: Difficulty level ("low", "medium", "high")
- `reasonTag`: Why this should be classified this way (for analysis)

**Tips:**
- Include 10+ labor keywords that should be candidates
- Include materials and fees that should be rejected
- Test edge cases: all-caps, abbreviations, ambiguous text
- Tag patterns to track weak areas

### 2. Run Classification Benchmark

```bash
./gradlew test --tests "*DeductionClassificationBenchmarkTest*"
```

### 3. Review Results

The test will:
1. Classify each line item
2. Score decision accuracy (found candidate when expected?)
3. Score category accuracy (correct deduction type?)
4. Group results by confidence band and reason tag
5. Generate `benchmark-classifications.csv` report

Example output:

```
📊 Deduction Classification Benchmark
================================================================================
Dataset: 25 test cases

[1/25] Testing: labor-001 - Sähkötyö, sisältää turvakytkin...
  ✓ Decision: candidate (expected: candidate)
     Category: Kotitalousvähennys (expected: Kotitalousvähennys)

...

======================== Summary ========================
Decision Accuracy (candidate vs rejected):  94.0%
Category Accuracy (when candidate):         88.5%
Total Test Cases:                           25

📊 By Confidence Band:
  high: 95.2% (21 cases)
  medium: 80.0% (3 cases)
  low: 66.7% (1 case)

🏷️  By Reason Tag:
  explicit_electrical_work: 100.0% (5 cases)
  anti_labour_keyword: 100.0% (8 cases)
  generic_labour_needs_context: 75.0% (4 cases)
```

### 4. CSV Report Format

`benchmark-classifications.csv`:

```csv
ID,Description,Expected Decision,Predicted Decision,Decision Match,Expected Category,Predicted Category,Category Match,Confidence,Reason
"labor-001","Sähkötyö, sisältää turvakytkin",candidate,candidate,true,Kotitalousvähennys,Kotitalousvähennys,true,high,explicit_electrical_work
"material-001","Sähkökaapeli DRAKA MMJ 3x1,5",rejected,rejected,true,REJECTED,NONE,true,high,material_not_labour
```

### 5. Interpret Results

| Metric | Meaning |
|--------|---------|
| **Decision Accuracy** | % of correct candidate/rejected calls |
| **Category Accuracy** | % of correct deduction type (when candidate found) |
| **By Confidence Band** | Group results by difficulty (helps find weak patterns) |
| **By Reason Tag** | Group by classification reason (helps identify improvement areas) |

Low scores on specific tags indicate areas to improve:
- `generic_labour_needs_context`: Add more context to prompt
- `material_not_labour`: Strengthen material rejection rules
- `anti_labour_keyword`: Check if anti-labour keywords are working

---

## Next Steps

1. Add 10+ real invoice examples to extraction dataset
2. Add 25+ line item examples to classification dataset
3. Run both benchmarks to establish baseline
4. Try different LLM prompts and test models
5. Identify weak patterns (use `reasonTag` and `confidenceBand` grouping)
6. Iterate on rules in `DeductionClassificationService`
7. Re-run benchmarks periodically to track improvement



