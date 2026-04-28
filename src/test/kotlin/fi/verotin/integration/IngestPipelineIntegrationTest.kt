package fi.verotin.integration

import fi.verotin.repository.DocumentChunkRepository
import fi.verotin.repository.InvoiceExtractionRepository
import fi.verotin.repository.SourceDocumentRepository
import fi.verotin.service.ChunkingService
import fi.verotin.service.DeductionClassificationService
import fi.verotin.service.IngestService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.io.ResourceLoader
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
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

        @DynamicPropertySource
        @JvmStatic
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
        }
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

    @Autowired
    private lateinit var classificationService: DeductionClassificationService

    @Autowired
    private lateinit var resourceLoader: ResourceLoader

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
        val longText = "Lorem ipsum dolor sit amet. ".repeat(100)  // ~2800 chars, exceeds DEFAULT_WINDOW (1500)

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

    @Test
    fun `pdf fixtures produce deduction candidates`() {
        val fixtures = mapOf(
            "Kuitti 128255003 - Verkkokauppa.com.pdf" to listOf(
                RequiredLineItem("ilmalampopumpun perusasennus", 1.0, listOf("705.0", "561.75")),
                RequiredLineItem("sisayksikko", 1.0, "596.94"),
                RequiredLineItem("ulkoyksikko", 1.0, "729.77"),
            ),
            "Lasku_2490.pdf" to listOf(
                RequiredLineItem("sahkotyo", 1.0, listOf("98.99", "78.88")),
                RequiredLineItem("maateline", 1.0, "79.0"),
                RequiredLineItem("kylmalinjan lisametri", 1.0, "60.0"),
            ),
            "Invoice_102509.pdf" to listOf(
                RequiredLineItem("YHDISTELMÄTERMOSTAATTI ONNLINE (ECOHELPPO16-RD 16A IP21)", 4.0, "417.08"),
                RequiredLineItem("KESKIOLEVY JUSSI (PUHELIN, SUOMI 3-NAP)", 1.0, "1.87"),
                RequiredLineItem("PEITELEVY JUSSI (4OS/IP21/85mm VAL)", 1.0, "9.01"),
                RequiredLineItem("PEITELEVY JUSSI (1OS+1/IP21/85mm VAL)", 1.0, "5.96"),
                RequiredLineItem("KYTKIN JUSSI (6/16AX/250V/IP21 UKJ 2X VAL)", 1.0, "6.59"),
                RequiredLineItem("KYTKIN JUSSI (5/16AX/250V/IP21 UKJ 0X VAL)", 1.0, "10.27"),
                RequiredLineItem("PISTORASIA JUSSI (2N/16A/IP20 UKJ HL VAL)", 1.0, "18.25"),
                RequiredLineItem("KESKIOLEVY JUSSI (UMPI, 85mm, RUUVIKIINNITYS)", 3.0, "12.99"),
                RequiredLineItem("ASENNUSKAAPELI DRAKA (MMJ 3x1,5 N R100 Eca)", 10.0, "22.20"),
                RequiredLineItem("ASENNUSKANAVA OBO (WDK 15x30x2000mm TARRA VA)", 4.0, "22.68"),
                RequiredLineItem("VALAISINPISTORASIA ABB (AKK 13 MAADOITETTU PINTA/UPPO)", 2.0, "17.26"),
                RequiredLineItem("PISTORASIA EXXACT (2S/16A/IP21 PPR 2X VAL)", 2.0, "42.80"),
                RequiredLineItem("Pientarvikelisä", 3.0, "41.43"),
                RequiredLineItem("Tuntityö", 7.0, "483.00"),
                RequiredLineItem("Huoltoauto", 1.0, "61.00"),
                RequiredLineItem("Laskutuslisä", 1.0, "9.00"),
            )
        )

        fixtures.forEach { (filename, expectedItems) ->
            val resource = resourceLoader.getResource("classpath:fixtures/$filename")
            assertTrue(resource.exists(), "Fixture missing: $filename")

            val bytes = resource.inputStream.use { it.readBytes() }
            val ingestResult = ingestService.ingest(
                rawBytes = bytes,
                filename = "it-$filename",
                contentType = "pdf",
            )
            assertNotNull(ingestResult, "Fixture should ingest: $filename")

            val actualItems = ingestResult.extraction.lineItems
            assertTrue(actualItems.isNotEmpty(), "No extracted line items for fixture: $filename")

            val unmatchedActual = actualItems.toMutableList()
            expectedItems.forEach { expected ->
                val matched = unmatchedActual.firstOrNull {
                    normalize(it.description).contains(normalize(expected.descriptionContains))
                }
                assertNotNull(matched, "Missing expected line '${expected.descriptionContains}' for fixture: $filename")
                assertEquals(expected.quantity, matched.quantity, "Quantity mismatch for '${expected.descriptionContains}' in fixture: $filename")
                assertAcceptedTotal(expected.acceptedTotals, matched, "Total mismatch for '${expected.descriptionContains}' in fixture: $filename")
                unmatchedActual.remove(matched)
            }
            assertTrue(
                actualItems.size >= expectedItems.size,
                "Extracted fewer lines than expected for fixture: $filename (expected at least ${expectedItems.size}, got ${actualItems.size})",
            )

            val candidates = classificationService.classify(ingestResult.extraction)
            assertTrue(candidates.isNotEmpty(), "Expected deduction candidates for fixture: $filename")
        }
    }

    private fun assertAcceptedTotal(expectedOptions: List<String>, actualItem: fi.verotin.domain.LineItem, message: String) {
        val actual = actualItem.total
        assertNotNull(actual, "$message (total missing)")
        val tolerance = BigDecimal("0.50")
        val matched = expectedOptions.any { expected ->
            val expectedDecimal = BigDecimal(expected)
            expectedDecimal.subtract(actual).abs() <= tolerance
        }
        assertTrue(matched, "$message (expected one of $expectedOptions actual=$actual)")
    }

    private fun normalize(input: String): String {
        return input.lowercase()
            .replace("ä", "a")
            .replace("ö", "o")
            .replace("å", "a")
            .replace(Regex("\\s+"), "")
            .trim()
    }

    private data class RequiredLineItem(
        val descriptionContains: String,
        val quantity: Double,
        val acceptedTotals: List<String>,
    ) {
        constructor(descriptionContains: String, quantity: Double, total: String) : this(
            descriptionContains = descriptionContains,
            quantity = quantity,
            acceptedTotals = listOf(total),
        )
    }
}

