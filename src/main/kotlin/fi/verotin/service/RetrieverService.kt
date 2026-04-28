package fi.verotin.service
import fi.verotin.config.OllamaProperties
import fi.verotin.ollama.OllamaClient
import fi.verotin.ollama.OllamaException
import fi.verotin.repository.DocumentChunkRepository
import fi.verotin.repository.TaxRuleChunkRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import kotlin.math.sqrt
/**
 * Retrieves relevant document and tax-rule chunks via semantic search.
 * Uses cosine similarity to rank chunks by relevance to a query.
 */
@Service
class RetrieverService(
    private val ollamaClient: OllamaClient,
    private val props: OllamaProperties,
    private val documentChunkRepo: DocumentChunkRepository,
    private val taxRuleChunkRepo: TaxRuleChunkRepository,
) {
    private val log = LoggerFactory.getLogger(RetrieverService::class.java)
    /**
     * Retrieve the top [limit] document chunks most similar to [query].
     */
    fun retrieveDocumentChunks(query: String, limit: Int = 5): List<String> {
        return try {
            val queryEmbedding = ollamaClient.embed(props.embedModel, query)
            val chunks = documentChunkRepo.findWithEmbeddings()
            val scored = chunks.mapNotNull { chunk ->
                chunk.embedding?.let { emb ->
                    val similarity = cosineSimilarity(queryEmbedding, emb)
                    chunk to similarity
                }
            }
            scored.sortedByDescending { it.second }
                .take(limit)
                .map { it.first.content }
        } catch (ex: OllamaException) {
            log.warn("Document retrieval failed due to embedding error: ${ex.message}")
            emptyList()
        }
    }
    /**
     * Retrieve the top [limit] tax-rule chunks most similar to [query].
     */
    fun retrieveTaxRules(query: String, limit: Int = 5): List<String> {
        return try {
            val queryEmbedding = ollamaClient.embed(props.embedModel, query)
            val chunks = taxRuleChunkRepo.findWithEmbeddings()
            val scored = chunks.mapNotNull { chunk ->
                chunk.embedding?.let { emb ->
                    val similarity = cosineSimilarity(queryEmbedding, emb)
                    chunk to similarity
                }
            }
            scored.sortedByDescending { it.second }
                .take(limit)
                .map { it.first.content }
        } catch (ex: OllamaException) {
            log.warn("Tax rule retrieval failed due to embedding error: ${ex.message}")
            emptyList()
        }
    }
    /**
     * Compute cosine similarity between two vectors.
     * Both must be the same non-zero length.
     */
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size || a.isEmpty()) return 0.0
        var dotProduct = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denominator = sqrt(normA) * sqrt(normB)
        return if (denominator > 0) dotProduct / denominator else 0.0
    }
}
