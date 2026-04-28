package fi.verotin.integration

import fi.verotin.config.OllamaProperties
import fi.verotin.domain.CandidateStatus
import fi.verotin.repository.DeductionCandidateRepository
import fi.verotin.repository.DocumentChunkRepository
import fi.verotin.repository.InvoiceExtractionRepository
import fi.verotin.repository.SourceDocumentRepository
import fi.verotin.service.ChunkingService
import fi.verotin.service.DeductionClassificationService
import fi.verotin.service.EmbeddingService
import fi.verotin.service.ExtractionService
import fi.verotin.service.IngestService
import fi.verotin.service.RetrieverService
import fi.verotin.ollama.OllamaClient
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@DisplayName("Ingest Pipeline Integration Tests")
class IngestPipelineIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        private val postgres = PostgreSQLContainer("pgvector/pgvector:pg16")
            .withDatabaseName("verotin_test")
            .withUsername("verotin")
            .withPassword("verotin")
    }

    @Autowired
    private lateinit var ingestService: IngestService

    @Autowired
    private lateinit var sourceDocumentRepo: SourceDocumentRepository

    @Autowired
    private lateinit var documentChunkRepo: DocumentChunkRepository

    @Autowired
    private lateinit var extractionRepo: InvoiceExtractionRepository

    @Autowired
    private lateinit var chunkingService: ChunkingService

    @BeforeEach
    fun setup() {
        // Tables are created by Flyway migrations automatically
    }

    @Test
    fun `ingest a text invoice creates source document and chunks`() {
        val invoiceText = """
            Invoice #2024-001
            Date: 2024-02-15
            Vendor: TechOffice Oy
            Total: €253.55 EUR
            Items:
            - Keyboard: €89.99
            - Monitor: €164.00
        """.trimIndent()

        val result = ingestService.ingest(
            rawBytes = invoiceText.toByteArray(),
            filename = "test-invoice.txt",
            contentType = "txt",
        )

        assertNotNull(result)
        assertEquals("test-invoice.txt", result.document.filename)
        assertTrue(result.chunkCount > 0)

        // Verify document was stored
        val storedDoc = sourceDocumentRepo.findById(result.document.id)
        assertNotNull(storedDoc)
        assertEquals("test-invoice.txt", storedDoc.filename)

        // Verify chunks were stored
        val chunks = documentChunkRepo.findBySourceDocumentId(result.document.id)
        assertEquals(result.chunkCount, chunks.size)
        assertTrue(chunks.all { it.sourceDocumentId == result.document.id })
    }

    @Test
    fun `duplicate documents are skipped`() {
        val invoiceText = "Invoice #123 for testing duplicate handling"
        val bytes = invoiceText.toByteArray()

        val result1 = ingestService.ingest(bytes, "duplicate-test.txt", "txt")
        assertNotNull(result1)

        // Second ingest with same content should be skipped
        val result2 = ingestService.ingest(bytes, "duplicate-test-2.txt", "txt")
        // Returns null when duplicate detected
        assertTrue(result2 == null || result2.document.id == result1.document.id)
    }

    @Test
    fun `chunking respects window and overlap parameters`() {
        val longText = "Lorem ipsum dolor sit amet. ".repeat(50)  // ~1400 chars

        val chunks = chunkingService.slidingWindows(longText)

        assertTrue(chunks.size > 1, "Long text should produce multiple chunks")
        chunks.forEach { chunk ->
            assertTrue(chunk.isNotEmpty(), "Chunks should not be empty")
            assertTrue(chunk.length <= ChunkingService.DEFAULT_WINDOW + 100, "Chunk exceeds window size")
        }
    }

    @Test
    fun `empty and whitespace-only documents are handled gracefully`() {
        val emptyResult = ingestService.ingest(
            rawBytes = ByteArray(0),
            filename = "empty.txt",
            contentType = "txt",
        )

        assertNotNull(emptyResult)
        val chunks = documentChunkRepo.findBySourceDocumentId(emptyResult.document.id)
        assertEquals(0, chunks.size, "Empty document should have no chunks")
    }

    @Test
    fun `invoice extraction processes document without crashing on LLM unavailability`() {
        // Note: This test assumes Ollama may not be available in test environment.
        // The extraction service logs a warning and returns a minimal record.
        val invoiceText = """
            Vendor: Test Company
            Invoice: #2024-001
            Date: 2024-02-15
            Total: €100.00
        """.trimIndent()

        val result = ingestService.ingest(
            rawBytes = invoiceText.toByteArray(),
            filename = "test.txt",
            contentType = "txt",
        )

        assertNotNull(result)
        assertNotNull(result.extraction)
        // Check that extraction record was created even if fields are null
        val stored = extractionRepo.findById(result.extraction.id)
        assertNotNull(stored)
    }
}

