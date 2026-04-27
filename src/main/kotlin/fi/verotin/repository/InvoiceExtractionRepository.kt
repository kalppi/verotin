package fi.verotin.repository
import com.fasterxml.jackson.databind.ObjectMapper
import fi.verotin.domain.InvoiceExtraction
import fi.verotin.domain.LineItem
import org.postgresql.util.PGobject
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.Date
import java.sql.ResultSet
import java.util.UUID
@Repository
class InvoiceExtractionRepository(
    private val jdbc: JdbcTemplate,
    private val objectMapper: ObjectMapper,
) {
    fun insert(ex: InvoiceExtraction) {
        val lineItemsJson = PGobject().apply {
            type = "jsonb"
            value = objectMapper.writeValueAsString(ex.lineItems)
        }
        jdbc.update(
            """
            INSERT INTO invoice_extractions
              (id, source_document_id, vendor_name, invoice_number, invoice_date,
               payment_date, total_amount, currency, vat_amount, labor_amount,
               material_amount, line_items, raw_llm_response, extracted_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            ex.id, ex.sourceDocumentId, ex.vendorName, ex.invoiceNumber,
            ex.invoiceDate?.let { Date.valueOf(it) },
            ex.paymentDate?.let { Date.valueOf(it) },
            ex.totalAmount, ex.currency, ex.vatAmount, ex.laborAmount,
            ex.materialAmount, lineItemsJson, ex.rawLlmResponse,
            java.sql.Timestamp.from(ex.extractedAt),
        )
    }
    fun findBySourceDocumentId(sourceDocumentId: UUID): InvoiceExtraction? =
        jdbc.query(
            "SELECT * FROM invoice_extractions WHERE source_document_id = ?",
            rowMapper,
            sourceDocumentId,
        ).firstOrNull()
    fun findAll(): List<InvoiceExtraction> =
        jdbc.query("SELECT * FROM invoice_extractions ORDER BY extracted_at DESC", rowMapper)
    fun findById(id: UUID): InvoiceExtraction? =
        jdbc.query("SELECT * FROM invoice_extractions WHERE id = ?", rowMapper, id).firstOrNull()
    fun findWithoutDeductionCandidates(): List<InvoiceExtraction> =
        jdbc.query(
            """
            SELECT ie.* FROM invoice_extractions ie
            LEFT JOIN deduction_candidates dc ON dc.invoice_extraction_id = ie.id
            WHERE dc.id IS NULL
            ORDER BY ie.extracted_at
            """.trimIndent(),
            rowMapper,
        )
    private val rowMapper = RowMapper { rs: ResultSet, _ ->
        val lineItemsRaw = rs.getString("line_items") ?: "[]"
        val lineItems = objectMapper.readValue(
            lineItemsRaw,
            objectMapper.typeFactory.constructCollectionType(List::class.java, LineItem::class.java),
        ) as List<LineItem>
        InvoiceExtraction(
            id = UUID.fromString(rs.getString("id")),
            sourceDocumentId = UUID.fromString(rs.getString("source_document_id")),
            vendorName = rs.getString("vendor_name"),
            invoiceNumber = rs.getString("invoice_number"),
            invoiceDate = rs.getDate("invoice_date")?.toLocalDate(),
            paymentDate = rs.getDate("payment_date")?.toLocalDate(),
            totalAmount = rs.getBigDecimal("total_amount"),
            currency = rs.getString("currency"),
            vatAmount = rs.getBigDecimal("vat_amount"),
            laborAmount = rs.getBigDecimal("labor_amount"),
            materialAmount = rs.getBigDecimal("material_amount"),
            lineItems = lineItems,
            rawLlmResponse = rs.getString("raw_llm_response"),
            extractedAt = rs.getTimestamp("extracted_at").toInstant(),
        )
    }
}
