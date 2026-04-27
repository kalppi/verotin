package fi.verotin.repository
import com.fasterxml.jackson.databind.ObjectMapper
import fi.verotin.domain.CandidateStatus
import fi.verotin.domain.DeductionCandidate
import org.postgresql.util.PGobject
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID
@Repository
class DeductionCandidateRepository(
    private val jdbc: JdbcTemplate,
    private val objectMapper: ObjectMapper,
) {
     fun insertBatch(candidates: List<DeductionCandidate>) {
         candidates.forEach { c ->
             val snippetsJson = PGobject().apply {
                 type = "jsonb"
                 value = objectMapper.writeValueAsString(c.evidenceSnippets)
             }
             jdbc.update(
                 """
                 INSERT INTO deduction_candidates
                   (id, invoice_extraction_id, category, deductible_amount, confidence,
                    justification, missing_information, suggested_next_action,
                    evidence_snippets, tax_year, status, raw_llm_response, created_at)
                 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                 """.trimIndent(),
                 c.id, c.invoiceExtractionId, c.category, c.deductibleAmount,
                 c.confidence, c.justification, c.missingInformation,
                 c.suggestedNextAction, snippetsJson, c.taxYear,
                 c.status.name.lowercase(), c.rawLlmResponse,
                 java.sql.Timestamp.from(c.createdAt),
             )
         }
     }
     fun insert(c: DeductionCandidate) {
         val snippetsJson = PGobject().apply {
             type = "jsonb"
             value = objectMapper.writeValueAsString(c.evidenceSnippets)
         }
         jdbc.update(
             """
             INSERT INTO deduction_candidates
               (id, invoice_extraction_id, category, deductible_amount, confidence,
                justification, missing_information, suggested_next_action,
                evidence_snippets, tax_year, status, raw_llm_response, created_at)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
             """.trimIndent(),
             c.id, c.invoiceExtractionId, c.category, c.deductibleAmount,
             c.confidence, c.justification, c.missingInformation,
             c.suggestedNextAction, snippetsJson, c.taxYear,
             c.status.name.lowercase(), c.rawLlmResponse,
             java.sql.Timestamp.from(c.createdAt),
         )
     }

    fun findByExtractionId(extractionId: UUID): List<DeductionCandidate> =
        jdbc.query(
            "SELECT * FROM deduction_candidates WHERE invoice_extraction_id = ? ORDER BY confidence DESC",
            rowMapper,
            extractionId,
        )
    fun findAll(): List<DeductionCandidate> =
        jdbc.query("SELECT * FROM deduction_candidates ORDER BY created_at DESC", rowMapper)
    fun updateStatus(id: UUID, status: CandidateStatus) {
        jdbc.update(
            "UPDATE deduction_candidates SET status = ? WHERE id = ?",
            status.name.lowercase(),
            id,
        )
    }
    @Suppress("UNCHECKED_CAST")
    private val rowMapper = RowMapper { rs: ResultSet, _ ->
        val snippetsRaw = rs.getString("evidence_snippets") ?: "[]"
        val snippets = objectMapper.readValue(
            snippetsRaw,
            objectMapper.typeFactory.constructCollectionType(List::class.java, String::class.java),
        ) as List<String>
        DeductionCandidate(
            id = UUID.fromString(rs.getString("id")),
            invoiceExtractionId = UUID.fromString(rs.getString("invoice_extraction_id")),
            category = rs.getString("category"),
            deductibleAmount = rs.getBigDecimal("deductible_amount"),
            confidence = rs.getDouble("confidence"),
            justification = rs.getString("justification"),
            missingInformation = rs.getString("missing_information"),
            suggestedNextAction = rs.getString("suggested_next_action"),
            evidenceSnippets = snippets,
            taxYear = rs.getObject("tax_year") as Int?,
            status = CandidateStatus.valueOf(rs.getString("status").uppercase()),
            rawLlmResponse = rs.getString("raw_llm_response"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
         )
     }
 }
