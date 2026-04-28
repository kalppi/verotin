package fi.verotin.unit

import fi.verotin.config.OllamaProperties
import fi.verotin.domain.CandidateStatus
import fi.verotin.domain.InvoiceExtraction
import fi.verotin.domain.LineItem
import fi.verotin.ollama.OllamaClient
import fi.verotin.ollama.OllamaException
import fi.verotin.repository.DeductionCandidateRepository
import fi.verotin.service.DeductionClassificationService
import fi.verotin.service.RetrieverService
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
import kotlin.test.assertTrue

@DisplayName("DeductionClassificationService Tests")
class DeductionClassificationServiceTest {

    private lateinit var ollamaClient: OllamaClient
    private lateinit var retrieverService: RetrieverService
    private lateinit var candidateRepo: DeductionCandidateRepository
    private lateinit var props: OllamaProperties
    private lateinit var classificationService: DeductionClassificationService

    @BeforeEach
    fun setup() {
        ollamaClient = mockk()
        retrieverService = mockk()
        candidateRepo = mockk()
        props = OllamaProperties(chatModel = "llama3")
        classificationService = DeductionClassificationService(
            ollamaClient = ollamaClient,
            retrievalService = retrieverService,
            props = props,
            candidateRepo = candidateRepo,
        )
    }

    private fun singleLineExtraction(description: String, total: BigDecimal? = null) = InvoiceExtraction(
        id = UUID.randomUUID(),
        sourceDocumentId = UUID.randomUUID(),
        vendorName = "Test Vendor",
        invoiceNumber = "INV-001",
        invoiceDate = LocalDate.of(2024, 2, 15),
        paymentDate = null,
        totalAmount = BigDecimal("250.00"),
        currency = "EUR",
        vatAmount = null,
        laborAmount = null,
        materialAmount = null,
        lineItems = listOf(LineItem(description = description, quantity = 1.0, unitPrice = total, total = total)),
        rawLlmResponse = "{}",
        extractedAt = Instant.now(),
    )

    @Test
    fun `classify extracts candidate from labour line and persists it`() {
        val extraction = singleLineExtraction("Ilmalämpöpumpun perusasennus", BigDecimal("250.00"))

        val llmResponse = """
            {
              "candidate": {
                "category": "kotitalousvahennys",
                "confidence": 0.80,
                "deductibleAmount": 250.00,
                "justification": "Installation labour is eligible for kotitalousvähennys",
                "missingInformation": "Confirmation work done at taxpayer home",
                "suggestedNextAction": "Verify address",
                "evidenceSnippets": ["Ilmalämpöpumpun perusasennus"]
              }
            }
        """.trimIndent()

        every { retrieverService.retrieveTaxRules(any(), any()) } returns emptyList()
        every { ollamaClient.chat(model = props.chatModel, messages = any(), jsonFormat = true) } returns llmResponse
        every { candidateRepo.insert(any()) } returns Unit

        val candidates = classificationService.classify(extraction, taxYear = 2025)

        assertEquals(1, candidates.size)
        assertEquals("kotitalousvahennys", candidates[0].category)
        assertEquals(0.80, candidates[0].confidence)
        assertEquals(CandidateStatus.PENDING, candidates[0].status)
        assertEquals(2025, candidates[0].taxYear)
        assertTrue(candidates[0].evidenceSnippets.contains("Ilmalämpöpumpun perusasennus"))
        verify { candidateRepo.insert(any()) }
    }

    @Test
    fun `classify returns empty list for product line`() {
        val extraction = singleLineExtraction("Sisäyksikkö", BigDecimal("900.00"))

        every { retrieverService.retrieveTaxRules(any(), any()) } returns emptyList()
        every { ollamaClient.chat(model = props.chatModel, messages = any(), jsonFormat = true) } returns """{"candidate": null}"""

        val candidates = classificationService.classify(extraction)

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `classify returns empty list when invoice has no line items`() {
        val extraction = InvoiceExtraction(
            id = UUID.randomUUID(),
            sourceDocumentId = UUID.randomUUID(),
            vendorName = "Test",
            invoiceNumber = null,
            invoiceDate = null,
            paymentDate = null,
            totalAmount = null,
            currency = null,
            vatAmount = null,
            laborAmount = null,
            materialAmount = null,
            lineItems = emptyList(),
            rawLlmResponse = "{}",
            extractedAt = Instant.now(),
        )

        val candidates = classificationService.classify(extraction)

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `classify returns empty list when LLM call fails`() {
        val extraction = singleLineExtraction("Tuntityö", BigDecimal("480.00"))

        every { retrieverService.retrieveTaxRules(any(), any()) } returns emptyList()
        every { ollamaClient.chat(model = props.chatModel, messages = any(), jsonFormat = true) } throws OllamaException("LLM API error")

        val candidates = classificationService.classify(extraction)

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `classify clamps confidence to valid range`() {
        val extraction = singleLineExtraction("Tuntityö", BigDecimal("100.00"))

        val llmResponse = """
            {
              "candidate": {
                "category": "kotitalousvahennys",
                "confidence": 1.9,
                "deductibleAmount": 100,
                "justification": "Labour work",
                "missingInformation": null,
                "suggestedNextAction": null,
                "evidenceSnippets": []
              }
            }
        """.trimIndent()

        every { retrieverService.retrieveTaxRules(any(), any()) } returns emptyList()
        every { ollamaClient.chat(model = props.chatModel, messages = any(), jsonFormat = true) } returns llmResponse
        every { candidateRepo.insert(any()) } returns Unit

        val candidates = classificationService.classify(extraction)

        assertEquals(1, candidates.size)
        assertEquals(1.0, candidates[0].confidence)
    }

    @Test
    fun `classify handles multiple line items classifying each independently`() {
        val extraction = InvoiceExtraction(
            id = UUID.randomUUID(),
            sourceDocumentId = UUID.randomUUID(),
            vendorName = "ElectroService",
            invoiceNumber = "INV-002",
            invoiceDate = LocalDate.of(2025, 1, 10),
            paymentDate = null,
            totalAmount = BigDecimal("700.00"),
            currency = "EUR",
            vatAmount = null,
            laborAmount = null,
            materialAmount = null,
            lineItems = listOf(
                LineItem("Asennuskaapeli", 1.0, BigDecimal("220.00"), BigDecimal("220.00")),
                LineItem("Tuntityö", 3.0, BigDecimal("160.00"), BigDecimal("480.00")),
            ),
            rawLlmResponse = "{}",
            extractedAt = Instant.now(),
        )

        val materialResponse = """{"candidate": null}"""
        val labourResponse = """
            {
              "candidate": {
                "category": "kotitalousvahennys",
                "confidence": 0.75,
                "deductibleAmount": 480.00,
                "justification": "Hourly electrical work is eligible for kotitalousvähennys",
                "missingInformation": "Home address confirmation",
                "suggestedNextAction": null,
                "evidenceSnippets": ["Tuntityö"]
              }
            }
        """.trimIndent()

        every { retrieverService.retrieveTaxRules(any(), any()) } returns emptyList()
        every {
            ollamaClient.chat(model = props.chatModel, messages = any(), jsonFormat = true)
        } returnsMany listOf(materialResponse, labourResponse)
        every { candidateRepo.insert(any()) } returns Unit

        val candidates = classificationService.classify(extraction)

        assertEquals(1, candidates.size)
        assertEquals("kotitalousvahennys", candidates[0].category)
        verify(exactly = 2) { ollamaClient.chat(model = props.chatModel, messages = any(), jsonFormat = true) }
    }

    @Test
    fun `classify ignores malformed JSON gracefully`() {
        val extraction = singleLineExtraction("Tuntityö", BigDecimal("300.00"))

        every { retrieverService.retrieveTaxRules(any(), any()) } returns emptyList()
        every { ollamaClient.chat(model = props.chatModel, messages = any(), jsonFormat = true) } returns "{ invalid json"

        val candidates = classificationService.classify(extraction)

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `classify retries once on malformed JSON and succeeds`() {
        val extraction = singleLineExtraction("Asennustyö", BigDecimal("200.00"))

        val validJson = """
            {
              "candidate": {
                "category": "kotitalousvahennys",
                "confidence": 0.7,
                "deductibleAmount": 200,
                "justification": "Installation work at home",
                "missingInformation": null,
                "suggestedNextAction": null,
                "evidenceSnippets": ["Asennustyö"]
              }
            }
        """.trimIndent()

        every { retrieverService.retrieveTaxRules(any(), any()) } returns emptyList()
        every {
            ollamaClient.chat(model = props.chatModel, messages = any(), jsonFormat = true)
        } returnsMany listOf("{ invalid json", validJson)
        every { candidateRepo.insert(any()) } returns Unit

        val candidates = classificationService.classify(extraction)

        assertEquals(1, candidates.size)
        assertEquals("kotitalousvahennys", candidates[0].category)
        verify(exactly = 2) { ollamaClient.chat(model = props.chatModel, messages = any(), jsonFormat = true) }
    }

    @Test
    fun `classify drops candidate with missing justification after retry`() {
        val extraction = singleLineExtraction("Tuntityö", BigDecimal("150.00"))

        val noJustification = """
            {
              "candidate": {
                "category": "kotitalousvahennys",
                "confidence": 0.6,
                "deductibleAmount": 150,
                "justification": "",
                "missingInformation": null,
                "suggestedNextAction": null,
                "evidenceSnippets": []
              }
            }
        """.trimIndent()

        every { retrieverService.retrieveTaxRules(any(), any()) } returns emptyList()
        every {
            ollamaClient.chat(model = props.chatModel, messages = any(), jsonFormat = true)
        } returns noJustification

        val candidates = classificationService.classify(extraction)

        assertTrue(candidates.isEmpty())
        verify(exactly = 2) { ollamaClient.chat(model = props.chatModel, messages = any(), jsonFormat = true) }
    }
}
