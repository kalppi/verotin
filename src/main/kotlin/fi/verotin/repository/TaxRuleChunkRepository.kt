package fi.verotin.repository
import com.pgvector.PGvector
import fi.verotin.domain.TaxRuleChunk
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID
@Repository
class TaxRuleChunkRepository(private val jdbc: JdbcTemplate) {
    fun insertBatch(chunks: List<TaxRuleChunk>) {
        chunks.forEach { chunk ->
            val pgVector = chunk.embedding?.let { PGvector(it) }
            jdbc.update(
                """
                INSERT INTO tax_rule_chunks (id, rule_source, chunk_index, content, embedding, created_at)
                VALUES (?, ?, ?, ?, ?::vector, ?)
                ON CONFLICT (rule_source, chunk_index) DO NOTHING
                """.trimIndent(),
                chunk.id,
                chunk.ruleSource,
                chunk.chunkIndex,
                chunk.content,
                pgVector?.toString(),
                java.sql.Timestamp.from(chunk.createdAt),
            )
        }
    }
     fun existsBySource(ruleSource: String): Boolean =
         (jdbc.queryForObject(
             "SELECT COUNT(*) FROM tax_rule_chunks WHERE rule_source = ?",
             Int::class.java,
             ruleSource,
         ) ?: 0) > 0
     fun findAll(): List<TaxRuleChunk> =
         jdbc.query("SELECT * FROM tax_rule_chunks ORDER BY created_at DESC", rowMapper)

    fun findSimilar(queryEmbedding: FloatArray, topK: Int = 5): List<TaxRuleChunk> {
        val pgVec = PGvector(queryEmbedding).toString()
        return jdbc.query(
            """
            SELECT * FROM tax_rule_chunks
            WHERE embedding IS NOT NULL
            ORDER BY embedding <=> ?::vector
            LIMIT ?
            """.trimIndent(),
            rowMapper,
            pgVec,
            topK,
        )
    }
    private val rowMapper = RowMapper { rs: ResultSet, _ ->
        val embeddingStr = rs.getString("embedding")
        TaxRuleChunk(
            id = UUID.fromString(rs.getString("id")),
            ruleSource = rs.getString("rule_source"),
            chunkIndex = rs.getInt("chunk_index"),
            content = rs.getString("content"),
            embedding = embeddingStr?.let { parseVector(it) },
            createdAt = rs.getTimestamp("created_at").toInstant(),
        )
    }
     private fun parseVector(s: String): FloatArray =
         s.trim('[', ']').split(',').map { it.trim().toFloat() }.toFloatArray()

     fun findWithEmbeddings(): List<TaxRuleChunk> =
         jdbc.query(
             "SELECT * FROM tax_rule_chunks WHERE embedding IS NOT NULL",
             rowMapper,
         )
 }
