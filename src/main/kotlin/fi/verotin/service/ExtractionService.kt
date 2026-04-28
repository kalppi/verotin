package fi.verotin.service
import fi.verotin.domain.InvoiceExtraction
import fi.verotin.domain.LineItem
import fi.verotin.domain.SourceDocument
import fi.verotin.ollama.OllamaClient
import fi.verotin.ollama.OllamaException
import fi.verotin.ollama.OllamaMessage
import fi.verotin.config.OllamaProperties
import fi.verotin.repository.InvoiceExtractionRepository
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
/**
 * Extracts structured invoice fields from a document using the LLM.
 * Uses JSON-mode output from Ollama to ensure parseable structured data.
 *
 * Error handling: extraction failures are logged and a minimal record is returned.
 * Every field is nullable; the LLM may not find all fields in every document.
 */
@Service
class ExtractionService(
    private val ollamaClient: OllamaClient,
    private val props: OllamaProperties,
    private val extractionRepo: InvoiceExtractionRepository,
) {
    private val log = LoggerFactory.getLogger(ExtractionService::class.java)
    private val objectMapper = jacksonObjectMapper()
    fun extract(doc: SourceDocument): InvoiceExtraction {
        val systemPrompt = """
            You are an invoice and receipt extraction system. 
            Analyze the provided document text and extract key invoice/receipt fields.
            Return ONLY a valid JSON object with the following schema:
            {
              "vendorName": "string or null",
              "invoiceNumber": "string or null",
              "invoiceDate": "YYYY-MM-DD or null",
              "paymentDate": "YYYY-MM-DD or null",
              "totalAmount": number (decimal) or null,
              "currency": "string (ISO 4217 code) or null",
              "vatAmount": number (decimal) or null,
              "laborAmount": number (decimal) or null",
              "materialAmount": number (decimal) or null",
              "lineItems": [
                {
                  "description": "string",
                  "quantity": number or null,
                  "unitPrice": number or null,
                  "total": number or null
                }
              ]
            }
            Rules:
            - Be conservative: if unsure, use null.
            - Dates must be in YYYY-MM-DD format.
            - Amounts are numbers (not strings).
            - Return empty array for lineItems if none found.
            - lineItems may have null fields where data is missing.
            - Finnish locale: look for "alv" or "vero" for VAT.
        """.trimIndent()
        val userPrompt = """
            Extract invoice fields from this document:
            ${doc.rawContent.take(4000)}
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
            log.error("Extraction failed for ${doc.filename}: ${ex.message}")
            return InvoiceExtraction(
                id = java.util.UUID.randomUUID(),
                sourceDocumentId = doc.id,
                vendorName = null,
                invoiceNumber = null,
                invoiceDate = null,
                paymentDate = null,
                totalAmount = null,
                currency = null,
                vatAmount = null,
                laborAmount = null,
                materialAmount = null,
                lineItems = emptyList(),
                rawLlmResponse = "ERROR: ${ex.message}",
                extractedAt = Instant.now(),
            ).also {
                extractionRepo.insert(it)
            }
        }
        val extraction = try {
            val parsed = objectMapper.readValue(rawResponse, ExtractionDto::class.java)
            InvoiceExtraction(
                id = java.util.UUID.randomUUID(),
                sourceDocumentId = doc.id,
                vendorName = parsed.vendorName?.trim().takeIf { !it.isNullOrBlank() },
                invoiceNumber = parsed.invoiceNumber?.trim().takeIf { !it.isNullOrBlank() },
                invoiceDate = parsed.invoiceDate?.let { parseDate(it) },
                paymentDate = parsed.paymentDate?.let { parseDate(it) },
                totalAmount = parsed.totalAmount?.let { BigDecimal(it.toString()) },
                currency = parsed.currency?.trim().takeIf { !it.isNullOrBlank() },
                vatAmount = parsed.vatAmount?.let { BigDecimal(it.toString()) },
                laborAmount = parsed.laborAmount?.let { BigDecimal(it.toString()) },
                materialAmount = parsed.materialAmount?.let { BigDecimal(it.toString()) },
                lineItems = parsed.lineItems?.map { item ->
                    LineItem(
                        description = item.description ?: "",
                        quantity = item.quantity?.toDouble(),
                        unitPrice = item.unitPrice?.let { BigDecimal(it.toString()) },
                        total = item.total?.let { BigDecimal(it.toString()) },
                    )
                } ?: emptyList(),
                rawLlmResponse = rawResponse,
                extractedAt = Instant.now(),
            )
        } catch (ex: Exception) {
            log.warn("Failed to parse extraction JSON, storing partial result", ex)
            InvoiceExtraction(
                id = java.util.UUID.randomUUID(),
                sourceDocumentId = doc.id,
                vendorName = null,
                invoiceNumber = null,
                invoiceDate = null,
                paymentDate = null,
                totalAmount = null,
                currency = null,
                vatAmount = null,
                laborAmount = null,
                materialAmount = null,
                lineItems = emptyList(),
                rawLlmResponse = rawResponse,
                extractedAt = Instant.now(),
            )
        }
        extractionRepo.insert(extraction)
        log.debug("Extracted invoice from ${doc.filename}: vendor=${extraction.vendorName}, amount=${extraction.totalAmount}")
        return extraction
    }
    private fun parseDate(dateStr: String?): LocalDate? {
        if (dateStr.isNullOrBlank()) return null
        return try {
            LocalDate.parse(dateStr.trim())
        } catch (ex: Exception) {
            log.debug("Failed to parse date: $dateStr")
            null
        }
    }
}
@JsonIgnoreProperties(ignoreUnknown = true)
data class ExtractionDto(
    val vendorName: String? = null,
    val invoiceNumber: String? = null,
    val invoiceDate: String? = null,
    val paymentDate: String? = null,
    val totalAmount: Number? = null,
    val currency: String? = null,
    val vatAmount: Number? = null,
    val laborAmount: Number? = null,
    val materialAmount: Number? = null,
    val lineItems: List<LineItemDto>? = null,
)
@JsonIgnoreProperties(ignoreUnknown = true)
data class LineItemDto(
    val description: String? = null,
    val quantity: Number? = null,
    val unitPrice: Number? = null,
    val total: Number? = null,
)
