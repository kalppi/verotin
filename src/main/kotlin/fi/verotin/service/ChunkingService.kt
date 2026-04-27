package fi.verotin.service
import fi.verotin.domain.DocumentChunk
import fi.verotin.domain.TaxRuleChunk
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID
/**
 * Splits document text into overlapping sliding-window chunks.
 *
 * Strategy: fixed character-based windows with overlap.
 * Character-based windows are simple, deterministic, and easy to unit-test.
 * Sentence-boundary splitting can be added later without changing the interface.
 */
@Service
class ChunkingService(
    private val windowSize: Int = DEFAULT_WINDOW,
    private val overlapSize: Int = DEFAULT_OVERLAP,
) {
    companion object {
        const val DEFAULT_WINDOW = 1500  // ~500 tokens at ~3 chars/token
        const val DEFAULT_OVERLAP = 200
    }
    /**
     * Produce [DocumentChunk] objects for a source document.
     * Chunks are pure data — no DB side effects here.
     */
    fun chunkDocument(
        sourceDocumentId: UUID,
        text: String,
    ): List<DocumentChunk> {
        val windows = slidingWindows(text)
        val now = Instant.now()
        return windows.mapIndexed { idx, window ->
            DocumentChunk(
                id = UUID.randomUUID(),
                sourceDocumentId = sourceDocumentId,
                chunkIndex = idx,
                content = window,
                embedding = null,
                createdAt = now,
            )
        }
    }
    /**
     * Produce [TaxRuleChunk] objects from tax-rule text.
     */
    fun chunkTaxRules(
        ruleSource: String,
        text: String,
    ): List<TaxRuleChunk> {
        val windows = slidingWindows(text)
        val now = Instant.now()
        return windows.mapIndexed { idx, window ->
            TaxRuleChunk(
                id = UUID.randomUUID(),
                ruleSource = ruleSource,
                chunkIndex = idx,
                content = window,
                embedding = null,
                createdAt = now,
            )
        }
    }
    /**
     * Core splitting logic, extracted as a pure function for easy testing.
     * Returns empty list for blank input; never returns empty-string chunks.
     */
    fun slidingWindows(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return emptyList()
        }
        if (trimmed.length <= windowSize) {
            return listOf(trimmed)
        }
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < trimmed.length) {
            val end = minOf(start + windowSize, trimmed.length)
            chunks.add(trimmed.substring(start, end))
            if (end == trimmed.length) {
                break
            }
            start += (windowSize - overlapSize)
        }
        return chunks
    }
}
