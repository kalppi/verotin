package fi.verotin.domain
import java.time.Instant
import java.util.UUID
/** A chunk of the Finnish tax-rule knowledge base, embedded for RAG retrieval. */
data class TaxRuleChunk(
    val id: UUID,
    val ruleSource: String,
    val chunkIndex: Int,
    val content: String,
    val embedding: FloatArray?,
    val createdAt: Instant,
)
