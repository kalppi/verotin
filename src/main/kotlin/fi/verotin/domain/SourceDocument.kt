package fi.verotin.domain
import java.time.Instant
import java.util.UUID
/**
 * A de-duplicated representation of an imported file (email body or attachment).
 * Raw content is kept for auditability and re-processing.
 */
data class SourceDocument(
    val id: UUID,
    val filename: String,
    /** 'email', 'pdf', 'txt' */
    val contentType: String,
    val rawContent: String,
    /** SHA-256 hex of the raw bytes — used for deduplication */
    val sha256Hash: String,
    val receivedAt: Instant?,
    val createdAt: Instant,
)
