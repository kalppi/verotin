package fi.verotin.service
import fi.verotin.config.OllamaProperties
import fi.verotin.domain.CandidateStatus
import fi.verotin.domain.DeductionCandidate
import fi.verotin.domain.InvoiceExtraction
import fi.verotin.ollama.OllamaClient
import fi.verotin.ollama.OllamaException
import fi.verotin.ollama.OllamaMessage
import fi.verotin.repository.DeductionCandidateRepository
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
/**
 * Classifies invoice extractions into possible tax-deduction candidates.
 * Uses the LLM to reason about Finnish tax rules and identify deductible expenses.
 *
 * IMPORTANT: Candidates are NOT final tax claims. They are reviewable suggestions
 * that require human verification before any tax filing action is taken.
 */
@Service
class DeductionClassificationService(
    private val ollamaClient: OllamaClient,
    private val retrievalService: RetrieverService,
    private val props: OllamaProperties,
    private val candidateRepo: DeductionCandidateRepository,
) {
    private val log = LoggerFactory.getLogger(DeductionClassificationService::class.java)
    private val objectMapper = jacksonObjectMapper()
    fun classify(extraction: InvoiceExtraction, taxYear: Int = 2024): List<DeductionCandidate> {
        // Retrieve relevant tax rules based on extracted vendor/category info.
        val taxRuleSnippets = retrievalService.retrieveTaxRules(
            query = buildQuery(extraction),
            limit = 5,
        )
        val systemPrompt = """
            You are a Finnish tax deduction classifier. 
            Your role is to identify POSSIBLE tax-deduction candidates from invoices,
            NOT to provide final tax advice or guarantees.
            Return a JSON array of possible deduction candidates based on:
            1. The extracted invoice fields
            2. Relevant Finnish tax rules (TVL 2024, deduction ceilings, etc.)
            For each candidate, estimate:
            - category (e.g., 'tyohuonevahennys', 'tyovaline', 'koulutus', 'polttoaine')
            - confidence (0.0 to 1.0, reflecting uncertainty, not legal certainty)
            - deductible_amount (the portion that may be deductible, null if not computable)
            - justification (evidence from document and rules)
            - missing_information (what additional docs or info would help)
            - suggested_next_action (e.g., 'Tarkista sopimentti')
            - evidence_snippets (quotes from document or rules supporting this)
            Return JSON in this format:
            {
              "candidates": [
                {
                  "category": "string",
                  "confidence": 0.75,
                  "deductibleAmount": null or number,
                  "justification": "string",
                  "missingInformation": "string or null",
                  "suggestedNextAction": "string or null",
                  "evidenceSnippets": ["snippet1", "snippet2"]
                }
              ]
            }
            CRITICAL RULES:
            - Only suggest categories that may legitimately be deductible under Finnish tax law.
            - Be conservative: low confidence if evidence is weak.
            - Do NOT suggest fictitious deduction categories.
            - Do NOT claim certainty where there is uncertainty.
            - Always note missing information.
        """.trimIndent()
        val userPrompt = """
            Classify this invoice extraction:
            Vendor: ${extraction.vendorName ?: "Unknown"}
            Amount: ${extraction.totalAmount ?: "Unknown"} ${extraction.currency ?: "EUR"}
            Date: ${extraction.invoiceDate ?: "Unknown"}
            Description (from document): ${extraction.lineItems.joinToString("; ") { it.description }}
            Relevant tax rules:
            ${taxRuleSnippets.joinToString("\n\n") { it }}
            Provide 1-3 possible deduction categories if applicable.
        """.trimIndent()
        val rawResponse = try {
            ollamaClient.chat(
                model = props.chatModel,
                messages = listOf(
                    OllamaMessage(role = "system", content = systemPrompt),
                    OllamaMessage(role = "user", content = userPrompt),
                ),
                jsonFormat = true,
            )
        } catch (ex: OllamaException) {
            log.error("Deduction classification failed for invoice ${extraction.id}: ${ex.message}")
            // Return no candidates on error; don't crash the ingest pipeline.
            return emptyList()
        }
        return try {
            val parsed = objectMapper.readValue(rawResponse, ClassificationResponseDto::class.java)
            val candidates = parsed.candidates?.mapNotNull { dto ->
                DeductionCandidate(
                    id = UUID.randomUUID(),
                    invoiceExtractionId = extraction.id,
                    category = dto.category ?: return@mapNotNull null,
                    deductibleAmount = dto.deductibleAmount?.let { BigDecimal(it.toString()) },
                    confidence = (dto.confidence ?: 0.5).coerceIn(0.0, 1.0),
                    justification = dto.justification ?: "No justification provided",
                    missingInformation = dto.missingInformation,
                    suggestedNextAction = dto.suggestedNextAction,
                    evidenceSnippets = dto.evidenceSnippets ?: emptyList(),
                    taxYear = taxYear,
                    status = CandidateStatus.PENDING,
                    rawLlmResponse = rawResponse,
                    createdAt = Instant.now(),
                )
            } ?: emptyList()
            candidates.forEach { candidateRepo.insert(it) }
            log.info("Classified invoice ${extraction.id} into ${candidates.size} deduction candidates")
            candidates
        } catch (ex: Exception) {
            log.warn("Failed to parse deduction classification JSON", ex)
            emptyList()
        }
    }
    private fun buildQuery(extraction: InvoiceExtraction): String {
        val parts = listOf(
            extraction.vendorName ?: "",
            extraction.lineItems.firstOrNull()?.description ?: "",
        ).filter { it.isNotEmpty() }
        return parts.joinToString(" ")
    }
}
@JsonIgnoreProperties(ignoreUnknown = true)
data class ClassificationResponseDto(
    val candidates: List<CandidateDto>? = null,
)
@JsonIgnoreProperties(ignoreUnknown = true)
data class CandidateDto(
    val category: String? = null,
    val confidence: Double? = null,
    val deductibleAmount: Number? = null,
    val justification: String? = null,
    val missingInformation: String? = null,
    val suggestedNextAction: String? = null,
    val evidenceSnippets: List<String>? = null,
)
