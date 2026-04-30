package fi.verotin.benchmark

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import fi.verotin.ollama.OllamaClient
import fi.verotin.ollama.OllamaMessage
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import org.springframework.web.reactive.function.client.WebClient

/**
 * Benchmark test that runs a grid search over all prompt/model combinations.
 *
 * Loads:
 * - Prompts from `src/test/resources/prompts/` (one .txt file per prompt)
 * - Models from the modelsToTest list below
 * - Test cases from `src/test/resources/benchmark-deductions.jsonl`
 *
 * Runs all combinations and reports the best-performing prompt+model pair.
 */
@DisplayName("Deduction Classification LLM Benchmark (Grid Search)")
class DeductionClassificationBenchmarkTest {

    companion object {
        private operator fun String.times(count: Int) = this.repeat(count)
    }

    private val objectMapper = jacksonObjectMapper()
    private val ollamaClient = OllamaClient(
        WebClient.builder()
            .baseUrl(System.getenv("OLLAMA_BASE_URL") ?: "http://localhost:11434")
            .build()
    )

    @Test
    @DisplayName("Benchmark: Grid search over prompts and models")
    fun runClassificationBenchmark() {
        // Configuration: which models to test
        val modelsToTest = listOf("mistral", "llama2", "llama3", "neural-chat")

        // Load all prompts from directory
        val prompts = loadPromptsFromDirectory()
        if (prompts.isEmpty()) {
            println("⚠️  No prompts found in src/test/resources/prompts/")
            return
        }

        val dataset = loadClassificationDataset()
        if (dataset.isEmpty()) {
            println("⚠️  No classification benchmark dataset found at classpath:benchmark-deductions.jsonl")
            return
        }

        println("\n📊 Deduction Classification LLM Benchmark (Grid Search)")
        println("=" * 80)
        println("Models to test: $modelsToTest")
        println("Prompts to test: ${prompts.keys}")
        println("Dataset: ${dataset.size} test cases")
        println("Total combinations: ${modelsToTest.size} × ${prompts.size} = ${modelsToTest.size * prompts.size}")

        assertFalse(modelsToTest.isEmpty(), "modelsToTest must not be empty")
        val allResults = mutableListOf<GridSearchResult>()

        // Grid search: for each model, for each prompt
        var combinationIdx = 1
        val totalCombinations = modelsToTest.size * prompts.size

        modelsToTest.forEach { model ->
            prompts.forEach { (promptName, promptText) ->
                println("\n" + "-" * 80)
                println("[$combinationIdx/$totalCombinations] Testing: $model + $promptName")
                println("-" * 80)

                val testResults = mutableListOf<ClassificationBenchmarkResult>()

                dataset.forEachIndexed { idx, testCase ->
                    if (idx % 5 == 0) {
                        println("  [$idx/${dataset.size}] Processing...")
                    }

                    // Call LLM
                    val predictedCategory = try {
                        val userPrompt = buildUserPrompt(testCase)
                        val response = ollamaClient.chat(
                            model = model,
                            messages = listOf(
                                OllamaMessage(role = "system", content = promptText),
                                OllamaMessage(role = "user", content = userPrompt),
                            ),
                            jsonFormat = true,
                        )
                        val parsed = objectMapper.readValue(response, ClassificationLlmResponse::class.java)
                        parsed.category ?: "NONE"
                    } catch (ex: Exception) {
                        "ERROR"
                    }

                    // Score
                    val categoryMatch = predictedCategory == testCase.expectedCategory
                    val score = ClassificationBenchmarkResult(
                        testCaseId = testCase.id,
                        description = testCase.description.take(60),
                        expectedCategory = testCase.expectedCategory,
                        predictedCategory = predictedCategory,
                        categoryMatch = categoryMatch,
                        confidenceBand = testCase.expectedConfidenceBand,
                        reasonTag = testCase.reasonTag,
                        modelUsed = model,
                    )
                    testResults.add(score)
                }

                // Calculate accuracy for this combination
                val accuracy = testResults.count { it.categoryMatch }.toDouble() / testResults.size * 100
                val gridResult = GridSearchResult(
                    model = model,
                    promptName = promptName,
                    accuracy = accuracy,
                    testsPassed = testResults.count { it.categoryMatch },
                    testsTotal = testResults.size,
                    results = testResults,
                )
                allResults.add(gridResult)

                println("  ✓ Accuracy: ${String.format("%.1f%%", accuracy)} (${gridResult.testsPassed}/${gridResult.testsTotal})")
                combinationIdx++
            }
        }

        // Find best combination
        val bestCombination = allResults.maxByOrNull { it.accuracy }
        if (bestCombination != null) {
            println("\n" + "=" * 80)
            println("🏆 BEST COMBINATION")
            println("=" * 80)
            println("Model:  ${bestCombination.model}")
            println("Prompt: ${bestCombination.promptName}")
            println("Accuracy: ${String.format("%.1f%%", bestCombination.accuracy)}")
            println("Passed: ${bestCombination.testsPassed}/${bestCombination.testsTotal}")
        }

        // Print detailed summary and export
        exportGridSearchResults(allResults)
    }

    private fun buildUserPrompt(testCase: ClassificationTestCase): String = """
        Classify the following invoice line item into a Finnish tax deduction category.
        
        Line Item Description: "${testCase.description}"
        Context: ${testCase.context ?: "UNKNOWN"}
        
        Respond with ONLY a JSON object containing:
        {
          "category": "<category name or null>",
          "confidence": <0.0 to 1.0>,
          "reasoning": "<brief explanation>"
        }
    """.trimIndent()

    private fun printClassificationSummary(results: List<ClassificationBenchmarkResult>) {
        if (results.isEmpty()) return

        val categoryAccuracy = results.count { it.categoryMatch }.toDouble() / results.size * 100

        val byReason = results.groupBy { it.reasonTag }
        val byConfidence = results.groupBy { it.confidenceBand }

        println("\n" + "=" * 80)
        println("📈 Summary")
        println("=" * 80)
        println("Category Accuracy:     ${String.format("%.1f%%", categoryAccuracy)}")
        println("Total Test Cases:      ${results.size}")

        println("\n🏷️  By Reason Tag:")
        byReason.forEach { (tag, cases) ->
            val acc = cases.count { it.categoryMatch }.toDouble() / cases.size * 100
            println("  $tag: ${String.format("%.1f%%", acc)} (${cases.size} cases)")
        }

        println("\n📊 By Confidence Band:")
        byConfidence.forEach { (band, cases) ->
            val acc = cases.count { it.categoryMatch }.toDouble() / cases.size * 100
            println("  $band: ${String.format("%.1f%%", acc)} (${cases.size} cases)")
        }
    }

    private fun exportGridSearchResults(allResults: List<GridSearchResult>) {
        // Summary CSV: one row per model+prompt combination
        val summaryFile = File("benchmark-grid-search-summary.csv")
        summaryFile.writeText("Model,Prompt,Accuracy %,Passed,Total\n")
        allResults.sortedByDescending { it.accuracy }.forEach { r ->
            summaryFile.appendText(
                "${r.model},${r.promptName},${String.format("%.1f", r.accuracy)}," +
                "${r.testsPassed},${r.testsTotal}\n"
            )
        }
        println("\n💾 Summary exported: benchmark-grid-search-summary.csv")

        // Detailed CSV: all test results
        val detailedFile = File("benchmark-grid-search-detailed.csv")
        detailedFile.writeText(
            "Model,Prompt,ID,Description,Expected Category,Predicted Category,Match,Confidence,Reason\n"
        )
        allResults.forEach { gridResult ->
            gridResult.results.forEach { r ->
                detailedFile.appendText(
                    "${gridResult.model},${gridResult.promptName}," +
                    "\"${r.testCaseId}\",\"${r.description}\"," +
                    "${r.expectedCategory},${r.predictedCategory}," +
                    "${r.categoryMatch},${r.confidenceBand},${r.reasonTag}\n"
                )
            }
        }
        println("💾 Detailed exported: benchmark-grid-search-detailed.csv")
    }

    private fun loadPromptsFromDirectory(): Map<String, String> {
        return try {
            val folder = File("src/test/resources/prompts")
            if (!folder.exists() || !folder.isDirectory) {
                println("Prompts directory not found: src/test/resources/prompts/")
                emptyMap()
            } else {
                val prompts = mutableMapOf<String, String>()
                folder.listFiles { file ->
                    file.isFile && file.name.endsWith(".txt")
                }?.forEach { file ->
                    val promptName = file.nameWithoutExtension
                    val promptText = file.readText()
                    prompts[promptName] = promptText
                }
                prompts
            }
        } catch (ex: Exception) {
            println("Failed to load prompts: ${ex.message}")
            emptyMap()
        }
    }

    private fun loadClassificationDataset(): List<ClassificationTestCase> {
        return try {
            val file = File("src/test/resources/benchmark-deductions.jsonl")
            if (!file.exists()) {
                println("Dataset file not found: benchmark-deductions.jsonl")
                emptyList()
            } else {
                file.bufferedReader().useLines { lines ->
                    lines
                        .filter { it.isNotBlank() }
                        .map { objectMapper.readValue(it, ClassificationTestCase::class.java) }
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
    val expectedConfidenceBand: String = "low",  // "low", "medium", "high"
    val reasonTag: String = "",
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ClassificationLlmResponse(
    val category: String? = null,
    val confidence: Double? = null,
    val reasoning: String? = null,
)

data class ClassificationBenchmarkResult(
    val testCaseId: String,
    val description: String,
    val expectedCategory: String,
    val predictedCategory: String,
    val categoryMatch: Boolean,
    val confidenceBand: String,
    val reasonTag: String,
    val modelUsed: String = "",
)

data class GridSearchResult(
    val model: String,
    val promptName: String,
    val accuracy: Double,
    val testsPassed: Int,
    val testsTotal: Int,
    val results: List<ClassificationBenchmarkResult>,
)



