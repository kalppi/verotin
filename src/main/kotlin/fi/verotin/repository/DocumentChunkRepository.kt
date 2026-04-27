package fi.verotin.repository
import com.pgvector.PGvector
import fi.verotin.domain.DocumentChunk
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID
@Repository
class DocumentChunkRepository(private val jdbc: JdbcTemplate) {
    fun insertBatch(chunks: List<DocumentChunk>) {
        chunks.forEach { chunk ->
            val pgVector = chunk.embedding?.let { PGvector(it) }
            jdbc.update(
                """
                INSERT INTO document_chunks (id, source_document_id, chunk_index, content, embedding, created_at)
                VALUES (?, ?, ?, ?, ?::vector, ?)
                ON CONFLICT (source_document_id, chunk_index) DO NOTHING
                """.trimIndent(),
                chunk.id,
                chunk.sourceDocumentId,
                chunk.chunkIndex,
                chunk.content,
                pgVector?.toString(),
                java.sql.Timestamp.from(chunk.createdAt),
            )
        }
    }
    fun findBySourceDocumentId(sourceDocumentId: UUID): List<DocumentChunk> =
        jdbc.query(
            "SELECT * FROM document_chunks WHERE source_document_id = ? ORDER BY chunk_index",
            rowMapper,
            sourceDocumentId,
        )
    fun findAll(): List<DocumentChunk> =
        jdbc.query("SELECT * FROM document_chunks ORDER BY created_at DESC", rowMapper)
    /**
     * Cosine similarity search against stored embeddings.
     * Returns the [topK] most similar chunks for the given query embedding.
     * The ivfflat index is used if it has been populated (requires at least 1 row).
     */
    fun findSimilar(queryEmbedding: FloatArray, topK: Int = 5): List<DocumentChunk> {
        val pgVec = PGvector(queryEmbedding).toString()
        return jdbc.query(
            """
            SELECT * FROM document_chunks
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
        DocumentChunk(
            id = UUID.fromString(rs.getString("id")),
            sourceDocumentId = UUID.fromString(rs.getString("source_document_id")),
            chunkIndex = rs.getInt("chunk_index"),
            content = rs.getString("content"),
            embedding = embeddingStr?.let { parseVector(it) },
            createdAt = rs.getTimestamp("created_at").toInstant(),
        )
    }
     /** Parse Postgres vector string "[0.1,0.2,...]" back to FloatArray. */
     private fun parseVector(s: String): FloatArray =
         s.trim('[', ']').split(',').map { it.trim().toFloat() }.toFloatArray()

     fun findWithEmbeddings(): List<DocumentChunk> =
         jdbc.query(
             "SELECT * FROM document_chunks WHERE embedding IS NOT NULL",
             rowMapper,
         )
 }
