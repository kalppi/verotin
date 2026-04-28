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
    companion object {
        private const val MAX_JSON_PARSE_ATTEMPTS = 2
    }

    private val log = LoggerFactory.getLogger(DeductionClassificationService::class.java)
    private val objectMapper = jacksonObjectMapper()
    fun classify(extraction: InvoiceExtraction, taxYear: Int = 2025): List<DeductionCandidate> {
        // Retrieve relevant tax rules based on extracted vendor/category info.
        val taxRuleSnippets = retrievalService.retrieveTaxRules(
            query = buildQuery(extraction),
            limit = 5,
        )
        val systemPrompt = """
            You are a Finnish tax deduction classifier.
            
            Your role is to identify POSSIBLE tax-deduction candidates from invoices,
            NOT to provide final tax advice or guarantees.
            
            ======================================================================
            NON-NEGOTIABLE RULES (HIGHEST PRIORITY)
            ======================================================================
            
            These rules override ALL other instructions.
            
            1. LABOUR REQUIREMENT
            A line MUST NOT be classified as kotitalousvähennys unless the SAME LINE
            contains a labour signal.
            
            2. LABOUR OVERRIDE (CRITICAL)
            If a line contains a strong labour signal, it MUST be treated as labour,
            even if:
            - the invoice contains many material/product lines
            - nearby lines contain materials
            
            Strong labour signals:
            - tuntityö
            - työ
            - työn osuus
            - asennustyö
            - sähkötyö
            - putkityö
            - huoltotyö
            - korjaustyö
            - asennus
            - perusasennus
            - huolto
            - korjaus
            - remontti
            - palvelu
            
            3. PRODUCT / MATERIAL EXCLUSION
            The following indicate product/material lines and are NEVER labour:
            - sisäyksikkö
            - ulkoyksikkö
            - laite
            - pumppu
            - kone
            - malli
            - tuotenumero
            - varaosa
            - tarvike
            
            4. NO CROSS-LINE INFERENCE
            Do NOT infer labour from nearby or sibling lines.
            
            5. BUNDLE RULE
            If a bundle/header line and detailed lines exist:
            - Use ONLY the detailed priced lines
            - NEVER generate duplicate candidates
            
            6. ONE EXPENSE → ONE CANDIDATE
            If multiple lines describe the same expense:
            - Generate ONLY ONE candidate
            - Use the most specific priced line
            
            If any rule conflicts → follow these rules.
            
            ======================================================================
            ANTI-LABOUR PATTERNS (CRITICAL)
            ======================================================================
            
            Words containing "asennus" are NOT automatically labour.
            
            The following are ALWAYS materials:
            - asennuskaapeli
            - asennuskanava
            - asennustarvike
            - asennusrasia
            - asennusputki
            - asennussarja
            
            Rule:
            If "asennus" is part of a compound word referring to a physical object,
            it is MATERIAL, not labour.
            
            Examples:
            - "asennuskaapeli" → material
            - "asennuskanava" → material
            
            ======================================================================
            CONFLICT RESOLUTION
            ======================================================================
            
            Priority:
            
            1. Strong labour signal on the SAME LINE → ALWAYS labour
            2. Strong material signal on the SAME LINE → material
            3. Context → only allowed if (1) exists
            
            Important:
            - "Tuntityö" ALWAYS wins over material context
            - Material-heavy invoice MUST NOT suppress labour lines
            
            ======================================================================
            STRUCTURAL RULES
            ======================================================================
            
            Treat invoice as hierarchy:
            
            - Bundle/header lines:
              - Often descriptive
              - May have price = 0
              - IGNORE if detailed lines exist
            
            - Detailed lines:
              - Actual charges
              - MUST be used
            
            Rules:
            - Prefer most specific priced line
            - Ignore zero-value bundle lines
            - Do NOT duplicate candidates
            
            ======================================================================
            LINE-LEVEL CLASSIFICATION
            ======================================================================
            
            STRICT ISOLATION RULE:
            Each line must be classified using ONLY its own content.
            
            Context rules:
            - MAY be used ONLY if line has labour signal
            - MUST NOT be used to turn product lines into labour
            
            NO GLOBAL BIAS:
            Do NOT classify based on overall invoice composition.
            
            ======================================================================
            GENERIC LABOUR RULE
            ======================================================================
            
            If a line contains a labour signal but no object (e.g. "Tuntityö"):
            
            - It MUST be treated as valid labour
            - It MUST NOT be skipped
            
            Then:
            - Use invoice context to infer category
            - NEVER use context to invalidate labour
            
            Example:
            "Tuntityö 483,00 €"
            → valid labour candidate
            
            ======================================================================
            HEAT PUMP RULE (EXPLICIT)
            ======================================================================
            
            - "ilmalämpöpumpun perusasennus"
              → kotitalousvähennys candidate (labour)
            
            - "ilmalämpöpumppu"
              → product
            
            - "sisäyksikkö"
              → product
            
            - "ulkoyksikkö"
              → product
            
            ======================================================================
            OUTPUT FORMAT
            ======================================================================
            
            Return JSON:
            
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
            
            ======================================================================
            OUTPUT RULES
            ======================================================================
            
            - Include ONLY possible deduction candidates
            - EXCLUDE product/material lines
            - EXCLUDE bundle/header lines
            
            Evidence rules:
            - evidenceSnippets MUST be UNIQUE across candidates
            - Same line MUST NOT justify multiple deductions
            
            ======================================================================
            CLASSIFICATION RULES
            ======================================================================
            
            - Only suggest legitimate Finnish tax categories
            - Be conservative
            - Do NOT invent categories
            - Do NOT claim certainty
            - Always include missingInformation
            
            ======================================================================
            FINAL PRIORITIES
            ======================================================================
            
            1. Correct structure (no duplicates, no bundle leakage)
            2. Labour vs product separation (strict)
            3. Labour lines MUST NOT be lost
            4. Conservative classification
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
        val messages = mutableListOf(
            OllamaMessage(role = "system", content = systemPrompt),
            OllamaMessage(role = "user", content = userPrompt),
        )

        var lastRawResponse = ""
        for (attempt in 1..MAX_JSON_PARSE_ATTEMPTS) {
            val rawResponse = try {
                ollamaClient.chat(
                    model = props.chatModel,
                    messages = messages,
                    jsonFormat = true,
                )
            } catch (ex: OllamaException) {
                log.error("Deduction classification failed for invoice ${extraction.id}: ${ex.message}")
                // Return no candidates on error; don't crash the ingest pipeline.
                return emptyList()
            }

            lastRawResponse = rawResponse
            val parsed = try {
                objectMapper.readValue(rawResponse, ClassificationResponseDto::class.java)
            } catch (ex: Exception) {
                if (attempt == MAX_JSON_PARSE_ATTEMPTS) {
                    log.warn("Failed to parse deduction classification JSON on attempt $attempt/$MAX_JSON_PARSE_ATTEMPTS, giving up", ex)
                    break
                }
                log.warn("Failed to parse deduction classification JSON on attempt $attempt/$MAX_JSON_PARSE_ATTEMPTS, retrying", ex)
                messages += OllamaMessage(role = "assistant", content = rawResponse)
                messages += OllamaMessage(role = "user", content = "Your previous response was not valid JSON. Return ONLY a valid JSON object with top-level key 'candidates'. No markdown, no code fences.")
                continue
            }

            val missingJustification = parsed.candidates?.any { it.justification.isNullOrBlank() } == true
            if (missingJustification && attempt < MAX_JSON_PARSE_ATTEMPTS) {
                log.warn("Some candidates are missing justification on attempt $attempt/$MAX_JSON_PARSE_ATTEMPTS, retrying")
                messages += OllamaMessage(role = "assistant", content = rawResponse)
                messages += OllamaMessage(role = "user", content = "Some candidates are missing a justification. Every candidate MUST have a non-empty 'justification' field explaining why it may be deductible. Return the full corrected JSON.")
                continue
            }

            val candidates = parsed.candidates?.mapNotNull { dto ->
                DeductionCandidate(
                    id = UUID.randomUUID(),
                    invoiceExtractionId = extraction.id,
                    category = dto.category ?: return@mapNotNull null,
                    deductibleAmount = dto.deductibleAmount?.let { BigDecimal(it.toString()) },
                    confidence = (dto.confidence ?: 0.5).coerceIn(0.0, 1.0),
                    justification = dto.justification?.takeIf { it.isNotBlank() } ?: return@mapNotNull null,
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
            return candidates
        }

        log.warn(
            "Failed to parse deduction classification JSON after $MAX_JSON_PARSE_ATTEMPTS attempts. Last response preview: ${
                lastRawResponse.take(
                    300
                )
            }"
        )
        return emptyList()
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
