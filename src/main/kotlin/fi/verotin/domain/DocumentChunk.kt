package fi.verotin.domain
import java.time.Instant
import java.util.UUID
/**
 * A sliding-window text chunk of a SourceDocument together with its embedding.
 * The embedding may be null while it is being generated.
 */
data class DocumentChunk(
    val id: UUID,
    val sourceDocumentId: UUID,
    val chunkIndex: Int,
    val content: String,
    /** Embedding vector from the Ollama embed model; null until generated. */
    val embedding: FloatArray?,
    val createdAt: Instant,
)
