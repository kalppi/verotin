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
        private const val MAX_PARSE_ATTEMPTS = 2
    }

    private val log = LoggerFactory.getLogger(DeductionClassificationService::class.java)
    private val objectMapper = jacksonObjectMapper()

    fun classify(extraction: InvoiceExtraction, taxYear: Int = 2025): List<DeductionCandidate> {
        if (extraction.lineItems.isEmpty()) {
            log.debug("No line items to classify for invoice ${extraction.id}")
            return emptyList()
        }

        // Retrieve tax rules once — shared across all line classifications.
        val taxRules = retrievalService.retrieveTaxRules(query = buildQuery(extraction), limit = 5)
        val invoiceContext = buildInvoiceContext(extraction)

        val candidates = extraction.lineItems.flatMapIndexed { idx, line ->
            val result = classifyLine(
                lineIndex = idx,
                lineDescription = line.description,
                lineTotal = line.total,
                invoiceContext = invoiceContext,
                taxRules = taxRules,
                extractionId = extraction.id,
                taxYear = taxYear,
            )
            result
        }

        candidates.forEach { candidateRepo.insert(it) }
        log.info(
            "Classified invoice ${extraction.id}: ${extraction.lineItems.size} lines → ${candidates.size} candidates"
        )
        return candidates
    }

    /**
     * Classify a single invoice line independently.
     * Each call asks the LLM to focus on ONE line only, with invoice context for reference.
     */
    private fun classifyLine(
        lineIndex: Int,
        lineDescription: String,
        lineTotal: BigDecimal?,
        invoiceContext: String,
        taxRules: List<String>,
        extractionId: UUID,
        taxYear: Int,
    ): List<DeductionCandidate> {
        if (lineDescription.isBlank()) return emptyList()

        val systemPrompt = """
            You are a Finnish tax deduction classifier.
            You are given ONE invoice line at a time. Decide if this single line is a possible tax deduction.
            
            ======================================================================
            CONSERVATIVE CLASSIFICATION PRINCIPLE
            ======================================================================
            
            DO NOT speculate or infer meaning from unclear text.
            
            If the line:
            - is a product code or SKU (e.g. "4OS/IP21/85mm VAL", model numbers, part numbers)
            - contains only abbreviations or codes you cannot decode with certainty
            - could mean multiple things but none are clearly a labour signal
            
            → Return { "candidate": null }
            
            ONLY classify as deductible if you are CONFIDENT the line describes a labour service
            or clearly deductible expense based on EXPLICIT FINNISH TAX RULES.
            
            ======================================================================
            LABOUR SIGNALS — line MUST contain a ROOT WORD related to these to be kotitalousvähennys:
            ======================================================================
            
            Case-insensitive: Match any capitalization (e.g., "Sähkötyö", "sähkötyö", "SÄHKÖTYÖ" are all labour)
            
            Root labour signals (match any form):
            
            työ (työ, työt, työstä, työhön, työtä, työn osuus, tuntityö, asennustyö, sähkötyö,
                 putkityö, huoltotyö, korjaustyö)
            
            asennus (asennus, asennuksen, asennukseen, asennusta, asennustyö, perusasennus,
                     asennukset)
            
            huolto (huolto, huollon, huollosta, huoltaminen, huoltotyö, huollon osuus, huoltopalvelu)
            
            korjaus (korjaus, korjauksen, korjaukseen, korjausta, korjaustyö, korjaaminen,
                     korjattava, korjauspalvelu)
            
            remontti (remontti, remontin, remonttiin, remonttia, koko remontti, osittainen remontti,
                      remontointityö)
            
            palvelu (palvelu, palvelun, palveluista, palvelusta, palvelut, lakisääteinen palvelu)
            
            Special case:
            "Tuntityö" (any form) = hourly labour → ALWAYS labour candidate if explicitly written
            
            ======================================================================
            PRODUCT / MATERIAL / NON-DEDUCTIBLE — NEVER labour:
            ======================================================================
            
            EXPLICIT material indicators:
            sisäyksikkö, ulkoyksikkö, laite, pumppu, kone, malli, tuotenumero,
            varaosa, tarvike, levy, kaapeli, kanava, kanava, rasia, putki, langan,
            johto, katkaisin, pistorasia, kytkin, vastus
            
            ANTI-LABOUR COMPOUNDS (contain "asennus" but are materials):
            asennuskaapeli, asennuskanava, asennustarvike, asennusrasia, asennusputki, asennussarja
            
            PRODUCT CODES AND SKUs:
            Lines that look like model numbers, part codes, serial numbers, or SKUs
            → NOT deductible. Example: "4OS/IP21/85mm VAL" → SKIP
            
            NEGATIVE SIGNALS (never deductible):
            laskutuslisä, toimitus, kuljetus, rahti, postitus, lähetys, matka, kuljetus,
            säilytys, vakuutus, provisio, komissio
            
            ======================================================================
            CONFLICT RESOLUTION (PRIORITY ORDER)
            ======================================================================
            
            1. NEGATIVE SIGNAL detected → { "candidate": null }
            2. Product code or SKU detected → { "candidate": null }
            3. Strong labour signal on this line → labour candidate (EVEN IF materials/components are mentioned)
            4. Strong material signal on this line (with NO labour signal) → { "candidate": null }
            5. If unsure → { "candidate": null }
            
            *** IMPORTANT: If a line contains a labour signal keyword + material keywords ***
            
            Pattern: "[Labour signal], sis. [material]" or "[Labour], [material]"
            Example: "Sähkötyö, sis. turvakytkin" → LABOUR (the switch is part of the electrical work)
            Example: "Asennus, levy + kaapeli" → LABOUR (materials are part of the installation service)
            
            Interpretation: The labour part is the primary service. Materials/components listed
            are just describing what's included in that service—NOT a reason to reject the candidate.
            
            Exception: If the line is ONLY materials with NO labour signal, reject it.
            Example: "Turvakytkin, levy, kaapeli" → { "candidate": null }
            
            NEVER infer labour from ambiguous abbreviations or codes.
            NEVER manufacture deduction categories.
            
            ======================================================================
            HEAT PUMP EXAMPLE
            ======================================================================
            
            - "ilmalämpöpumpun perusasennus" → kotitalousvähennys (labour)
            - "ilmalämpöpumppu" → NOT deductible (product)
            - "sisäyksikkö" → NOT deductible (product)
            - "ulkoyksikkö" → NOT deductible (product)
            
            ======================================================================
            OUTPUT FORMAT
            ======================================================================
            
            If this line is a possible deduction (HIGH CONFIDENCE):
            {
              "candidate": {
                "category": "string",
                "confidence": 0.75,
                "deductibleAmount": null or number,
                "justification": "string — REQUIRED, explain why this line may be deductible based on EXPLICIT text",
                "missingInformation": "string or null",
                "suggestedNextAction": "string or null",
                "evidenceSnippets": ["exact text from this line"]
              }
            }
            
            If this line is NOT deductible or uncertain:
            { "candidate": null }
            
            Return ONLY this JSON. No markdown, no explanation outside JSON.
        """.trimIndent()

        val userPrompt = """
            Invoice context:
            $invoiceContext
            
            Relevant tax rules:
            ${taxRules.joinToString("\n\n") { it }}
            
            Classify this single invoice line (line ${lineIndex + 1}):
            Description: $lineDescription
            Amount: ${lineTotal ?: "unknown"}
        """.trimIndent()

        val messages = mutableListOf(
            OllamaMessage(role = "system", content = systemPrompt),
            OllamaMessage(role = "user", content = userPrompt),
        )

        var lastRaw = ""
        for (attempt in 1..MAX_PARSE_ATTEMPTS) {
            val rawResponse = try {
                ollamaClient.chat(model = props.chatModel, messages = messages, jsonFormat = true)
            } catch (ex: OllamaException) {
                log.error("LLM call failed for line $lineIndex of invoice $extractionId: ${ex.message}")
                return emptyList()
            }

            lastRaw = rawResponse

            val dto = try {
                objectMapper.readValue(rawResponse, LineClassificationResponseDto::class.java)
            } catch (ex: Exception) {
                if (attempt == MAX_PARSE_ATTEMPTS) {
                    log.warn("Failed to parse line classification JSON for line $lineIndex (attempt $attempt), skipping", ex)
                    return emptyList()
                }
                messages += OllamaMessage(role = "assistant", content = rawResponse)
                messages += OllamaMessage(role = "user", content = "Invalid JSON. Return ONLY a JSON object with key 'candidate' (object or null). No markdown.")
                continue
            }

            val candidate = dto.candidate ?: return emptyList() // not deductible

            if (candidate.justification.isNullOrBlank()) {
                if (attempt < MAX_PARSE_ATTEMPTS) {
                    log.warn("Missing justification for line $lineIndex (attempt $attempt), retrying")
                    messages += OllamaMessage(role = "assistant", content = rawResponse)
                    messages += OllamaMessage(role = "user", content = "The candidate is missing a justification. 'justification' MUST be a non-empty string explaining why this line may be deductible.")
                    continue
                }
                log.warn("Dropping candidate for line $lineIndex — justification still missing after $MAX_PARSE_ATTEMPTS attempts")
                return emptyList()
            }

            return listOf(
                DeductionCandidate(
                    id = UUID.randomUUID(),
                    invoiceExtractionId = extractionId,
                    category = candidate.category ?: return emptyList(),
                    deductibleAmount = candidate.deductibleAmount?.let { BigDecimal(it.toString()) },
                    confidence = (candidate.confidence ?: 0.5).coerceIn(0.0, 1.0),
                    justification = candidate.justification,
                    missingInformation = candidate.missingInformation,
                    suggestedNextAction = candidate.suggestedNextAction,
                    evidenceSnippets = candidate.evidenceSnippets ?: emptyList(),
                    taxYear = taxYear,
                    status = CandidateStatus.PENDING,
                    rawLlmResponse = rawResponse,
                    createdAt = Instant.now(),
                )
            )
        }

        log.warn("Line $lineIndex gave up after $MAX_PARSE_ATTEMPTS attempts. Preview: ${lastRaw.take(200)}")
        return emptyList()
    }

    private fun buildInvoiceContext(extraction: InvoiceExtraction): String =
        """
        Vendor: ${extraction.vendorName ?: "Unknown"}
        Invoice date: ${extraction.invoiceDate ?: "Unknown"}
        Total amount: ${extraction.totalAmount ?: "Unknown"} ${extraction.currency ?: "EUR"}
        """.trimIndent()

    private fun buildQuery(extraction: InvoiceExtraction): String {
        val parts = listOf(
            extraction.vendorName ?: "",
            extraction.lineItems.firstOrNull()?.description ?: "",
        ).filter { it.isNotEmpty() }
        return parts.joinToString(" ")
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class LineClassificationResponseDto(
    val candidate: CandidateDto? = null,
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
