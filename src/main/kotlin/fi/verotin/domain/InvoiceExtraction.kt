package fi.verotin.domain
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
/**
 * Structured invoice/receipt fields extracted by the LLM from a SourceDocument.
 * Every field is nullable because the LLM may not find all fields in every document.
 */
data class InvoiceExtraction(
    val id: UUID,
    val sourceDocumentId: UUID,
    val vendorName: String?,
    val invoiceNumber: String?,
    val invoiceDate: LocalDate?,
    val paymentDate: LocalDate?,
    val totalAmount: BigDecimal?,
    val currency: String?,
    val vatAmount: BigDecimal?,
    val laborAmount: BigDecimal?,
    val materialAmount: BigDecimal?,
    val lineItems: List<LineItem>,
    /** Raw LLM response for debugging and auditability. */
    val rawLlmResponse: String,
    val extractedAt: Instant,
)
data class LineItem(
    val description: String,
    val quantity: Double?,
    val unitPrice: BigDecimal?,
    val total: BigDecimal?,
)
