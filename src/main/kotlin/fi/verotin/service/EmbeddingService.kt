package fi.verotin.service
import fi.verotin.config.OllamaProperties
import fi.verotin.domain.DocumentChunk
import fi.verotin.domain.TaxRuleChunk
import fi.verotin.ollama.OllamaClient
import fi.verotin.ollama.OllamaException
import fi.verotin.repository.DocumentChunkRepository
import fi.verotin.repository.TaxRuleChunkRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
/**
 * Generates embeddings for chunks via Ollama and persists the enriched chunks.
 * Embedding failures are logged and skipped — a chunk without an embedding is
 * still useful for keyword search even if it misses semantic retrieval.
 */
@Service
class EmbeddingService(
    private val ollamaClient: OllamaClient,
    private val props: OllamaProperties,
    private val documentChunkRepo: DocumentChunkRepository,
    private val taxRuleChunkRepo: TaxRuleChunkRepository,
) {
    private val log = LoggerFactory.getLogger(EmbeddingService::class.java)
    fun embedAndStoreDocumentChunks(chunks: List<DocumentChunk>): List<DocumentChunk> {
        val embedded = chunks.map { chunk ->
            val withEmbedding = addEmbedding(chunk.content)?.let { vec ->
                chunk.copy(embedding = vec)
            } ?: chunk
            withEmbedding
        }
        documentChunkRepo.insertBatch(embedded)
        log.info("Stored ${embedded.size} document chunks (${embedded.count { it.embedding != null }} with embeddings)")
        return embedded
    }
    fun embedAndStoreTaxRuleChunks(chunks: List<TaxRuleChunk>): List<TaxRuleChunk> {
        val embedded = chunks.map { chunk ->
            val withEmbedding = addEmbedding(chunk.content)?.let { vec ->
                chunk.copy(embedding = vec)
            } ?: chunk
            withEmbedding
        }
        taxRuleChunkRepo.insertBatch(embedded)
        log.info("Stored ${embedded.size} tax-rule chunks (${embedded.count { it.embedding != null }} with embeddings)")
        return embedded
    }
    private fun addEmbedding(text: String): FloatArray? {
        return try {
            ollamaClient.embed(props.embedModel, text)
        } catch (ex: OllamaException) {
            log.warn("Embedding failed, storing chunk without vector: ${ex.message}")
            null
        }
    }
}
