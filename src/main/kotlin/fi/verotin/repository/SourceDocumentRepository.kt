package fi.verotin.repository
import fi.verotin.domain.SourceDocument
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
@Repository
class SourceDocumentRepository(private val jdbc: JdbcTemplate) {
    fun insert(doc: SourceDocument) {
        jdbc.update(
            """
            INSERT INTO source_documents (id, filename, content_type, raw_content, sha256_hash, received_at, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (sha256_hash) DO NOTHING
            """.trimIndent(),
            doc.id, doc.filename, doc.contentType, doc.rawContent,
            doc.sha256Hash, doc.receivedAt?.let { java.sql.Timestamp.from(it) },
            java.sql.Timestamp.from(doc.createdAt),
        )
    }
    fun existsByHash(sha256Hash: String): Boolean =
        (jdbc.queryForObject(
            "SELECT COUNT(*) FROM source_documents WHERE sha256_hash = ?",
            Int::class.java,
            sha256Hash,
        ) ?: 0) > 0
    fun findAll(): List<SourceDocument> =
        jdbc.query("SELECT * FROM source_documents ORDER BY created_at DESC", rowMapper)
    fun findById(id: UUID): SourceDocument? =
        jdbc.query("SELECT * FROM source_documents WHERE id = ?", rowMapper, id).firstOrNull()
    private val rowMapper = RowMapper { rs: ResultSet, _ ->
        SourceDocument(
            id = UUID.fromString(rs.getString("id")),
            filename = rs.getString("filename"),
            contentType = rs.getString("content_type"),
            rawContent = rs.getString("raw_content"),
            sha256Hash = rs.getString("sha256_hash"),
            receivedAt = rs.getTimestamp("received_at")?.toInstant(),
            createdAt = rs.getTimestamp("created_at").toInstant(),
        )
    }
}
