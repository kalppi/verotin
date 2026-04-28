package fi.verotin.unit

import fi.verotin.config.OllamaProperties
import fi.verotin.domain.SourceDocument
import fi.verotin.ollama.OllamaClient
import fi.verotin.ollama.OllamaException
import fi.verotin.ollama.OllamaMessage
import fi.verotin.repository.InvoiceExtractionRepository
import fi.verotin.service.ExtractionService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DisplayName("ExtractionService Tests")
class ExtractionServiceTest {

    private lateinit var ollamaClient: OllamaClient
    private lateinit var extractionRepo: InvoiceExtractionRepository
    private lateinit var props: OllamaProperties
    private lateinit var extractionService: ExtractionService

    @BeforeEach
    fun setup() {
        ollamaClient = mockk()
        extractionRepo = mockk()
        props = OllamaProperties(chatModel = "llama3", embeddingDimensions = 1024)
        extractionService = ExtractionService(
            ollamaClient = ollamaClient,
            props = props,
            extractionRepo = extractionRepo,
        )
    }

    @Test
    fun `extract parses valid JSON invoice response`() {
        val doc = SourceDocument(
            id = UUID.randomUUID(),
            filename = "invoice.txt",
            contentType = "txt",
            rawContent = "Invoice #2024-001\nVendor: Tech Store\nAmount: €200.00\nDate: 2024-02-15",
            sha256Hash = "abc123",
            receivedAt = null,
            createdAt = Instant.now(),
        )

        val llmResponse = """
            {
              "vendorName": "Tech Store",
              "invoiceNumber": "2024-001",
              "invoiceDate": "2024-02-15",
              "paymentDate": null,
              "totalAmount": 200.00,
              "currency": "EUR",
              "vatAmount": 48.00,
              "laborAmount": null,
              "materialAmount": null,
              "lineItems": [
                {
                  "description": "Keyboard",
                  "quantity": 1,
                  "unitPrice": 50.00,
                  "total": 50.00
                }
              ]
            }
        """.trimIndent()

        every {
            ollamaClient.chat(
                model = props.chatModel,
                messages = any(),
                jsonFormat = true,
            )
        } returns llmResponse
        every { extractionRepo.insert(any()) } returns Unit

        val extraction = extractionService.extract(doc)

        assertEquals("Tech Store", extraction.vendorName)
        assertEquals("2024-001", extraction.invoiceNumber)
        assertEquals(LocalDate.of(2024, 2, 15), extraction.invoiceDate)
        assertEquals(0, BigDecimal("200.0").compareTo(extraction.totalAmount))
        assertEquals("EUR", extraction.currency)
        assertEquals(0, BigDecimal("48.0").compareTo(extraction.vatAmount))
        assertEquals(1, extraction.lineItems.size)
        assertEquals("Keyboard", extraction.lineItems[0].description)
        assertNull(extraction.paymentDate)
        assertNull(extraction.laborAmount)
        verify { extractionRepo.insert(any()) }
    }

    @Test
    fun `extract handles null fields gracefully`() {
        val doc = SourceDocument(
            id = UUID.randomUUID(),
            filename = "minimal.txt",
            contentType = "txt",
            rawContent = "Some minimal content",
            sha256Hash = "xyz789",
            receivedAt = null,
            createdAt = Instant.now(),
        )

        val llmResponse = """
            {
              "vendorName": null,
              "invoiceNumber": null,
              "invoiceDate": null,
              "paymentDate": null,
              "totalAmount": null,
              "currency": null,
              "vatAmount": null,
              "laborAmount": null,
              "materialAmount": null,
              "lineItems": []
            }
        """.trimIndent()

        every {
            ollamaClient.chat(
                model = props.chatModel,
                messages = any(),
                jsonFormat = true,
            )
        } returns llmResponse
        every { extractionRepo.insert(any()) } returns Unit

        val extraction = extractionService.extract(doc)

        assertNull(extraction.vendorName)
        assertNull(extraction.invoiceNumber)
        assertNull(extraction.invoiceDate)
        assertNull(extraction.totalAmount)
        assertEquals(0, extraction.lineItems.size)
        assertTrue(extraction.rawLlmResponse.isNotEmpty())
        verify { extractionRepo.insert(any()) }
    }

    @Test
    fun `extract stores raw LLM response for auditability`() {
        val doc = SourceDocument(
            id = UUID.randomUUID(),
            filename = "invoice.txt",
            contentType = "txt",
            rawContent = "Test invoice",
            sha256Hash = "test",
            receivedAt = null,
            createdAt = Instant.now(),
        )

        val llmResponse = """{"vendorName": "Test Vendor"}"""

        every {
            ollamaClient.chat(
                model = props.chatModel,
                messages = any(),
                jsonFormat = true,
            )
        } returns llmResponse
        every { extractionRepo.insert(any()) } returns Unit

        val extraction = extractionService.extract(doc)

        assertEquals(llmResponse, extraction.rawLlmResponse)
    }

    @Test
    fun `extract returns minimal record on LLM failure`() {
        val doc = SourceDocument(
            id = UUID.randomUUID(),
            filename = "invoice.txt",
            contentType = "txt",
            rawContent = "Test",
            sha256Hash = "test",
            receivedAt = null,
            createdAt = Instant.now(),
        )

        every {
            ollamaClient.chat(
                model = props.chatModel,
                messages = any(),
                jsonFormat = true,
            )
        } throws OllamaException("Connection timeout")
        every { extractionRepo.insert(any()) } returns Unit

        val extraction = extractionService.extract(doc)

        assertNull(extraction.vendorName)
        assertNull(extraction.invoiceNumber)
        assertTrue(extraction.rawLlmResponse.contains("ERROR"))
        verify { extractionRepo.insert(any()) }
    }

    @Test
    fun `extract parses dates in YYYY-MM-DD format`() {
        val doc = SourceDocument(
            id = UUID.randomUUID(),
            filename = "invoice.txt",
            contentType = "txt",
            rawContent = "Invoice dated 2024-12-25",
            sha256Hash = "test",
            receivedAt = null,
            createdAt = Instant.now(),
        )

        val llmResponse = """
            {
              "vendorName": "Gift Store",
              "invoiceDate": "2024-12-25",
              "paymentDate": "2025-01-10",
              "totalAmount": 123.45,
              "currency": "EUR",
              "lineItems": []
            }
        """.trimIndent()

        every {
            ollamaClient.chat(
                model = props.chatModel,
                messages = any(),
                jsonFormat = true,
            )
        } returns llmResponse
        every { extractionRepo.insert(any()) } returns Unit

        val extraction = extractionService.extract(doc)

        assertEquals(LocalDate.of(2024, 12, 25), extraction.invoiceDate)
        assertEquals(LocalDate.of(2025, 1, 10), extraction.paymentDate)
    }

    @Test
    fun `extract handles malformed JSON response gracefully`() {
        val doc = SourceDocument(
            id = UUID.randomUUID(),
            filename = "invoice.txt",
            contentType = "txt",
            rawContent = "Test",
            sha256Hash = "test",
            receivedAt = null,
            createdAt = Instant.now(),
        )

        every {
            ollamaClient.chat(
                model = props.chatModel,
                messages = any(),
                jsonFormat = true,
            )
        } returns "{ broken json }"
        every { extractionRepo.insert(any()) } returns Unit

        val extraction = extractionService.extract(doc)

        // Should return record with raw response, not crash
        assertNotNull(extraction)
        assertEquals("{ broken json }", extraction.rawLlmResponse)
        assertNull(extraction.vendorName)
        verify { extractionRepo.insert(any()) }
    }

    @Test
    fun `extract trims and validates vendor name`() {
        val doc = SourceDocument(
            id = UUID.randomUUID(),
            filename = "invoice.txt",
            contentType = "txt",
            rawContent = "Test",
            sha256Hash = "test",
            receivedAt = null,
            createdAt = Instant.now(),
        )

        val llmResponse = """
            {
              "vendorName": "   Trimmed Vendor   ",
              "totalAmount": 100,
              "currency": "EUR",
              "lineItems": []
            }
        """.trimIndent()

        every {
            ollamaClient.chat(
                model = props.chatModel,
                messages = any(),
                jsonFormat = true,
            )
        } returns llmResponse
        every { extractionRepo.insert(any()) } returns Unit

        val extraction = extractionService.extract(doc)

        assertEquals("Trimmed Vendor", extraction.vendorName)
    }
}

