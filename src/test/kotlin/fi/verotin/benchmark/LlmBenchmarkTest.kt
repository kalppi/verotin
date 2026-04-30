package fi.verotin.benchmark

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import fi.verotin.ollama.OllamaClient
import fi.verotin.ollama.OllamaMessage
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.io.ResourceLoader
import org.springframework.test.context.ActiveProfiles
import java.io.File
import java.math.BigDecimal

/**
 * Benchmark test for comparing LLM extraction quality across different prompts/models.
 *
 * Usage:
 * 1. Create a benchmark dataset at `src/test/resources/benchmark-fixtures.jsonl`
 * 2. Each line is a JSON object with: document text, vendor name, invoiceNumber, total, lineItems
 * 3. Run with: ./gradlew test --tests "*LlmBenchmarkTest*"
 * 4. Compare results in the generated CSV report
 *
 * Example dataset line:
 * {
 *   "text": "Invoice #2024-001\nVendor: TechCorp\n...",
 *   "expectedVendor": "TechCorp",
 *   "expectedInvoiceNumber": "2024-001",
 *   "expectedTotal": "1234.56",
 *   "expectedLineItems": [
 *     { "description": "Widget", "quantity": 2, "unitPrice": 617.28, "total": 1234.56 }
 *   ]
 * }
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("LLM Extraction Benchmark")
class LlmBenchmarkTest {

    @Autowired
    private lateinit var resourceLoader: ResourceLoader

    @Autowired
    private lateinit var ollamaClient: OllamaClient

    @Test
    @DisplayName("Benchmark: Load and score all test cases")
    fun runBenchmark() {
        val dataset = loadBenchmarkDataset()
        if (dataset.isEmpty()) {
            println("⚠️  No benchmark dataset found at classpath:benchmark-fixtures.jsonl")
            return
        }

        println("\n📊 LLM Extraction Benchmark")
        println("=" * 80)
        println("Dataset: ${dataset.size} test cases")

        val results = mutableListOf<BenchmarkResult>()

        dataset.forEachIndexed { idx, testCase ->
            println("\n[${idx + 1}/${dataset.size}] Testing: ${testCase.text.take(60)}...")

            // Extract using the current LLM
            val extracted = try {
                val systemPrompt = buildSystemPrompt()
                val userPrompt = "Extract invoice fields from this document:\n${testCase.text}"
                val response = ollamaClient.chat(
                    model = "mistral",
                    messages = listOf(
                        OllamaMessage(role = "system", content = systemPrompt),
                        OllamaMessage(role = "user", content = userPrompt),
                    ),
                    jsonFormat = true,
                )
                val mapper = jacksonObjectMapper()
                mapper.readValue(response, ExtractionResponse::class.java)
            } catch (ex: Exception) {
                println("  ❌ Extraction failed: ${ex.message}")
                null
            } ?: ExtractionResponse()

            // Score the result
            val score = scoreExtraction(testCase, extracted)
            results.add(score)

            println("  Vendor: ${score.vendorMatch} | Invoice: ${score.invoiceMatch} | Total: ${score.totalMatch}")
            println("  Line Items: ${score.lineItemMatch} | Overall: ${String.format("%.1f%%", score.overallScore)}")
        }

        // Print summary
        printBenchmarkSummary(results)

        // Export CSV report
        exportCsvReport(results)
    }

    private fun buildSystemPrompt(): String = """
        You are an invoice and receipt extraction system.
        Extract key fields: vendorName, invoiceNumber, invoiceDate, totalAmount, currency, vatAmount, lineItemsTable.
        Return ONLY valid JSON. Column types: CODE, DESCRIPTION, QUANTITY, UNIT_PRICE, NET_TOTAL, GROSS_TOTAL, VAT_RATE, UNKNOWN.
    """.trimIndent()

    private fun scoreExtraction(testCase: BenchmarkTestCase, extracted: ExtractionResponse): BenchmarkResult {
        val vendorMatch = scoreStringMatch(testCase.expectedVendor, extracted.vendorName)
        val invoiceMatch = scoreStringMatch(testCase.expectedInvoiceNumber, extracted.invoiceNumber)
        val totalMatch = scoreDecimalMatch(testCase.expectedTotal, extracted.totalAmount)

        // Score line items
        val lineItemMatch = scoreLineItems(testCase.expectedLineItems, extracted.lineItems)

        val overallScore = (vendorMatch + invoiceMatch + totalMatch + lineItemMatch * 0.5) / 3.5 * 100

        return BenchmarkResult(
            testCaseText = testCase.text.take(80),
            vendorMatch = vendorMatch * 100,
            invoiceMatch = invoiceMatch * 100,
            totalMatch = totalMatch * 100,
            lineItemMatch = lineItemMatch * 100,
            overallScore = overallScore,
        )
    }

    private fun scoreStringMatch(expected: String?, actual: String?): Double {
        if (expected == null && actual == null) return 1.0
        if (expected == null || actual == null) return 0.0
        val norm1 = expected.lowercase().trim()
        val norm2 = actual.lowercase().trim()
        return if (norm1 == norm2) 1.0 else levenshteinSimilarity(norm1, norm2)
    }

    private fun scoreDecimalMatch(expected: String?, actual: Number?): Double {
        if (expected == null && actual == null) return 1.0
        if (expected == null || actual == null) return 0.0
        return try {
            val exp = BigDecimal(expected)
            val act = BigDecimal(actual.toString())
            val diff = exp.subtract(act).abs()
            val tolerance = BigDecimal("0.50")
            if (diff <= tolerance) 1.0 else maxOf(0.0, 1.0 - (diff.toDouble() / tolerance.toDouble()))
        } catch (ex: Exception) {
            0.0
        }
    }

    private fun scoreLineItems(expected: List<ExpectedLineItem>?, actual: List<ExtractionLineItem>?): Double {
        if (expected == null && actual == null) return 1.0
        if (expected == null || actual == null) return 0.0
        if (expected.isEmpty() && actual.isEmpty()) return 1.0
        if (expected.isEmpty() || actual.isEmpty()) return 0.0

        var totalScore = 0.0
        expected.forEach { exp ->
            val match = actual.minByOrNull { act ->
                val descSim = levenshteinSimilarity(exp.description.lowercase(), act.description.lowercase())
                1.0 - descSim
            }
            if (match != null) {
                val descScore = levenshteinSimilarity(exp.description.lowercase(), match.description.lowercase())
                val qtyScore = if (match.quantity == exp.quantity) 1.0 else 0.8
                val priceScore = scoreDecimalMatch(exp.unitPrice.toString(), match.unitPrice)
                val itemTotalScore = scoreDecimalMatch(exp.total.toString(), match.total)
                totalScore += (descScore + qtyScore + priceScore + itemTotalScore) / 4.0
            }
        }
        return totalScore / expected.size
    }

    private fun levenshteinSimilarity(s1: String, s2: String): Double {
        val dist = levenshteinDistance(s1, s2)
        val maxLen = maxOf(s1.length, s2.length)
        return 1.0 - (dist.toDouble() / maxLen)
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j
        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,      // delete
                    dp[i][j - 1] + 1,      // insert
                    dp[i - 1][j - 1] + if (s1[i - 1] == s2[j - 1]) 0 else 1  // substitute
                )
            }
        }
        return dp[s1.length][s2.length]
    }

    private fun printBenchmarkSummary(results: List<BenchmarkResult>) {
        if (results.isEmpty()) return

        val avgVendor = results.map { it.vendorMatch }.average()
        val avgInvoice = results.map { it.invoiceMatch }.average()
        val avgTotal = results.map { it.totalMatch }.average()
        val avgLineItems = results.map { it.lineItemMatch }.average()
        val avgOverall = results.map { it.overallScore }.average()

        println("\n" + "=" * 80)
        println("📈 Summary")
        println("=" * 80)
        println("Vendor Name:    ${String.format("%.1f%%", avgVendor)}")
        println("Invoice Number: ${String.format("%.1f%%", avgInvoice)}")
        println("Total Amount:   ${String.format("%.1f%%", avgTotal)}")
        println("Line Items:     ${String.format("%.1f%%", avgLineItems)}")
        println("Overall Score:  ${String.format("%.1f%%", avgOverall)}")
    }

    private fun exportCsvReport(results: List<BenchmarkResult>) {
        val reportFile = File("benchmark-results.csv")
        reportFile.writeText("Test Case,Vendor %,Invoice %,Total %,Line Items %,Overall %\n")
        results.forEach { r ->
            reportFile.appendText(
                "${r.testCaseText}," +
                "${String.format("%.1f", r.vendorMatch)}," +
                "${String.format("%.1f", r.invoiceMatch)}," +
                "${String.format("%.1f", r.totalMatch)}," +
                "${String.format("%.1f", r.lineItemMatch)}," +
                "${String.format("%.1f", r.overallScore)}\n"
            )
        }
        println("\n💾 Report exported: benchmark-results.csv")
    }

    private fun loadBenchmarkDataset(): List<BenchmarkTestCase> {
        return try {
            val resource = resourceLoader.getResource("classpath:benchmark-fixtures.jsonl")
            if (!resource.exists()) {
                println("Dataset file not found: benchmark-fixtures.jsonl")
                emptyList()
            } else {
                val mapper = jacksonObjectMapper()
                resource.inputStream.bufferedReader().useLines { lines ->
                    lines
                        .filter { it.isNotBlank() }
                        .map { mapper.readValue(it, BenchmarkTestCase::class.java) }
                        .toList()
                }
            }
        } catch (ex: Exception) {
            println("Failed to load benchmark dataset: ${ex.message}")
            emptyList()
        }
    }

    companion object {
        private operator fun String.times(count: Int) = this.repeat(count)
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class BenchmarkTestCase(
    val text: String = "",
    val expectedVendor: String? = null,
    val expectedInvoiceNumber: String? = null,
    val expectedTotal: String? = null,
    val expectedLineItems: List<ExpectedLineItem>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ExpectedLineItem(
    val description: String = "",
    val quantity: Double? = null,
    val unitPrice: Double? = null,
    val total: Double? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ExtractionResponse(
    val vendorName: String? = null,
    val invoiceNumber: String? = null,
    val invoiceDate: String? = null,
    val totalAmount: Number? = null,
    val lineItems: List<ExtractionLineItem>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ExtractionLineItem(
    val description: String = "",
    val quantity: Double? = null,
    val unitPrice: Number? = null,
    val total: Number? = null,
)

data class BenchmarkResult(
    val testCaseText: String,
    val vendorMatch: Double,
    val invoiceMatch: Double,
    val totalMatch: Double,
    val lineItemMatch: Double,
    val overallScore: Double,
)

