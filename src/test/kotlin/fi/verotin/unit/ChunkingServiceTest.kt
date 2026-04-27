package fi.verotin.unit
import fi.verotin.service.ChunkingService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
@DisplayName("ChunkingService Tests")
class ChunkingServiceTest {
    private val service = ChunkingService(windowSize = 100, overlapSize = 20)
    @Test
    fun `empty text produces no chunks`() {
        val chunks = service.slidingWindows("")
        assertEquals(0, chunks.size)
    }
    @Test
    fun `text shorter than window returns single chunk`() {
        val text = "Short text"
        val chunks = service.slidingWindows(text)
        assertEquals(1, chunks.size)
        assertEquals(text, chunks[0])
    }
    @Test
    fun `long text is split with overlap`() {
        val text = "a".repeat(250)  // 250 chars, window=100, overlap=20
        val chunks = service.slidingWindows(text)
        assertTrue(chunks.size > 1)
        // With overlap, first chunk should be [0:100], second [80:180], etc.
        assertEquals(100, chunks[0].length)
    }
    @Test
    fun `whitespace is trimmed`() {
        val text = "   content   "
        val chunks = service.slidingWindows(text)
        assertEquals(1, chunks.size)
        assertEquals("content", chunks[0])
    }
    @Test
    fun `chunks overlap correctly`() {
        val text = ("a".repeat(50)) + ("b".repeat(50)) + ("c".repeat(50))
        val chunks = service.slidingWindows(text)
        assertTrue(chunks.size > 1)
        // Check that consecutive chunks overlap (share some characters)
        for (i in 0 until chunks.size - 1) {
            val overlap = chunks[i].takeLast(20)
            assertTrue(chunks[i + 1].startsWith(overlap.take(10)) || 
                      chunks[i + 1].contains(overlap.take(10)))
        }
    }
}
