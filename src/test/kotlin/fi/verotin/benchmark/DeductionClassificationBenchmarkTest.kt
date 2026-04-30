package fi.verotin.benchmark

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import fi.verotin.service.DeductionClassificationService
import fi.verotin.domain.InvoiceExtraction
import fi.verotin.domain.LineItem
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.io.ResourceLoader
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.io.File

/**
 * Benchmark test for comparing deduction classification quality.
 * Tests how well the classification service identifies tax deduction candidates.
 *
 * Dataset format (JSONL): each line is a test case with ground truth classification.
 * See `benchmark-deductions.jsonl` for examples.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@DisplayName("Deduction Classification Benchmark")
class DeductionClassificationBenchmarkTest {

    companion object {
        @Container
        @JvmStatic
        private val postgres = PostgreSQLContainer("pgvector/pgvector:pg16")
            .withDatabaseName("verotin_test")
            .withUsername("verotin")
            .withPassword("verotin")

        @DynamicPropertySource
        @JvmStatic
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
        }

        private operator fun String.times(count: Int) = this.repeat(count)
    }

    @Autowired
    private lateinit var resourceLoader: ResourceLoader

    @Autowired
    private lateinit var classificationService: DeductionClassificationService

    @Test
    @DisplayName("Benchmark: Classify line items and score against ground truth")
    fun runClassificationBenchmark() {
        val dataset = loadClassificationDataset()
        if (dataset.isEmpty()) {
            println("⚠️  No classification benchmark dataset found at classpath:benchmark-deductions.jsonl")
            return
        }

        println("\n📊 Deduction Classification Benchmark")
        println("=" * 80)
        println("Dataset: ${dataset.size} test cases")

        val results = mutableListOf<ClassificationBenchmarkResult>()

        dataset.forEachIndexed { idx, testCase ->
            println("\n[${idx + 1}/${dataset.size}] Testing: ${testCase.id} - ${testCase.description.take(50)}...")

            // Create a minimal invoice with a single line item
            val lineItem = LineItem(
                description = testCase.description,
                quantity = null,
                unitPrice = null,
                total = null,
            )
            val invoice = InvoiceExtraction(
                id = java.util.UUID.randomUUID(),
                sourceDocumentId = java.util.UUID.randomUUID(),
                vendorName = null,
                invoiceNumber = null,
                invoiceDate = null,
                paymentDate = null,
                totalAmount = null,
                currency = null,
                vatAmount = null,
                laborAmount = null,
                materialAmount = null,
                lineItems = listOf(lineItem),
                rawLlmResponse = "",
                extractedAt = java.time.Instant.now(),
            )

            // Classify
            val deductions = try {
                classificationService.classify(invoice)
            } catch (ex: Exception) {
                println("  ❌ Classification failed: ${ex.message}")
                emptyList()
            }

            // Score: did we find a candidate when expected?
            val expectedCandidate = testCase.expectedDecision == "candidate"
            val foundCandidate = deductions.isNotEmpty()
            val decisionMatch = (expectedCandidate == foundCandidate)

            // Score: category match (if we found a candidate)
            val categoryMatch = if (foundCandidate && deductions.isNotEmpty()) {
                val predictedCategory = deductions[0].category
                predictedCategory == testCase.expectedCategory
            } else {
                !expectedCandidate  // Correct if we don't need a candidate and didn't find one
            }

            val score = ClassificationBenchmarkResult(
                testCaseId = testCase.id,
                description = testCase.description.take(60),
                expectedDecision = testCase.expectedDecision,
                expectedCategory = testCase.expectedCategory,
                predictedDecision = if (foundCandidate) "candidate" else "rejected",
                predictedCategory = deductions.firstOrNull()?.category ?: "NONE",
                decisionMatch = decisionMatch,
                categoryMatch = categoryMatch,
                confidenceBand = testCase.expectedConfidenceBand,
                reasonTag = testCase.reasonTag,
            )
            results.add(score)

            val status = if (score.decisionMatch && score.categoryMatch) "✓" else "✗"
            println("  $status Decision: ${score.predictedDecision} (expected: ${score.expectedDecision})")
            println("     Category: ${score.predictedCategory} (expected: ${score.expectedCategory})")
        }

        // Print summary
        printClassificationSummary(results)

        // Export CSV report
        exportClassificationCsvReport(results)
    }

    private fun printClassificationSummary(results: List<ClassificationBenchmarkResult>) {
        if (results.isEmpty()) return

        val decisionAccuracy = results.count { it.decisionMatch }.toDouble() / results.size * 100
        val categoryAccuracy = results.count { it.categoryMatch }.toDouble() / results.size * 100

        val byConfidence = results.groupBy { it.confidenceBand }
        val byReason = results.groupBy { it.reasonTag }

        println("\n" + "=" * 80)
        println("📈 Summary")
        println("=" * 80)
        println("Decision Accuracy (candidate vs rejected): ${String.format("%.1f%%", decisionAccuracy)}")
        println("Category Accuracy (when candidate):       ${String.format("%.1f%%", categoryAccuracy)}")
        println("Total Test Cases:                         ${results.size}")

        println("\n📊 By Confidence Band:")
        byConfidence.forEach { (band, cases) ->
            val acc = cases.count { it.decisionMatch }.toDouble() / cases.size * 100
            println("  $band: ${String.format("%.1f%%", acc)} (${cases.size} cases)")
        }

        println("\n🏷️  By Reason Tag:")
        byReason.forEach { (tag, cases) ->
            val acc = cases.count { it.decisionMatch }.toDouble() / cases.size * 100
            println("  $tag: ${String.format("%.1f%%", acc)} (${cases.size} cases)")
        }
    }

    private fun exportClassificationCsvReport(results: List<ClassificationBenchmarkResult>) {
        val reportFile = File("benchmark-classifications.csv")
        reportFile.writeText(
            "ID,Description,Expected Decision,Predicted Decision,Decision Match," +
            "Expected Category,Predicted Category,Category Match,Confidence,Reason\n"
        )
        results.forEach { r ->
            reportFile.appendText(
                "\"${r.testCaseId}\"," +
                "\"${r.description}\"," +
                "${r.expectedDecision}," +
                "${r.predictedDecision}," +
                "${r.decisionMatch}," +
                "${r.expectedCategory}," +
                "${r.predictedCategory}," +
                "${r.categoryMatch}," +
                "${r.confidenceBand}," +
                "${r.reasonTag}\n"
            )
        }
        println("\n💾 Report exported: benchmark-classifications.csv")
    }

    private fun loadClassificationDataset(): List<ClassificationTestCase> {
        return try {
            val resource = resourceLoader.getResource("classpath:benchmark-deductions.jsonl")
            if (!resource.exists()) {
                println("Dataset file not found: benchmark-deductions.jsonl")
                emptyList()
            } else {
                val mapper = jacksonObjectMapper()
                resource.inputStream.bufferedReader().useLines { lines ->
                    lines
                        .filter { it.isNotBlank() }
                        .map { mapper.readValue(it, ClassificationTestCase::class.java) }
                        .toList()
                }
            }
        } catch (ex: Exception) {
            println("Failed to load classification dataset: ${ex.message}")
            emptyList()
        }
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class ClassificationTestCase(
    val id: String = "",
    val description: String = "",
    val context: String? = null,
    val expectedCategory: String = "",
    val expectedDecision: String = "candidate",  // "candidate" or "rejected"
    val expectedConfidenceBand: String = "low",  // "low", "medium", "high"
    val reasonTag: String = "",
)

data class ClassificationBenchmarkResult(
    val testCaseId: String,
    val description: String,
    val expectedDecision: String,
    val expectedCategory: String,
    val predictedDecision: String,
    val predictedCategory: String,
    val decisionMatch: Boolean,
    val categoryMatch: Boolean,
    val confidenceBand: String,
    val reasonTag: String,
)



