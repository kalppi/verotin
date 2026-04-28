package fi.verotin.unit

import fi.verotin.config.OllamaProperties
import fi.verotin.domain.CandidateStatus
import fi.verotin.domain.DeductionCandidate
import fi.verotin.domain.InvoiceExtraction
import fi.verotin.ollama.OllamaClient
import fi.verotin.ollama.OllamaException
import fi.verotin.ollama.OllamaMessage
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

    @Test
    fun `classify extracts candidates from LLM response and persists them`() {
        val extraction = InvoiceExtraction(
            id = UUID.randomUUID(),
            sourceDocumentId = UUID.randomUUID(),
            vendorName = "TechOffice Oy",
            invoiceNumber = "2024-001",
            invoiceDate = LocalDate.of(2024, 2, 15),
            paymentDate = null,
            totalAmount = BigDecimal("253.55"),
            currency = "EUR",
            vatAmount = BigDecimal("49.07"),
            laborAmount = null,
            materialAmount = null,
            lineItems = emptyList(),
            rawLlmResponse = "{}",
            extractedAt = Instant.now(),
        )

        val taxRules = listOf(
            "Home office deductions are allowed if space is exclusively used for work.",
            "Office equipment under €850 can be fully deducted.",
        )

        val llmResponse = """
            {
              "candidates": [
                {
                  "category": "tyohuonevahennys",
                  "confidence": 0.65,
                  "deductibleAmount": 127.78,
                  "justification": "Office equipment qualifies for home office deduction",
                  "missingInformation": "Confirmation of exclusive work use",
                  "suggestedNextAction": "Verify home office setup",
                  "evidenceSnippets": ["Office equipment", "Work-related"]
                }
              ]
            }
        """.trimIndent()

        every { retrieverService.retrieveTaxRules(any(), any()) } returns taxRules
        every {
            ollamaClient.chat(
                model = props.chatModel,
                messages = any(),
                jsonFormat = true,
            )
        } returns llmResponse
        every { candidateRepo.insert(any()) } returns Unit

        val candidates = classificationService.classify(extraction, taxYear = 2024)

        assertEquals(1, candidates.size)
        val candidate = candidates[0]
        assertEquals("tyohuonevahennys", candidate.category)
        assertEquals(0.65, candidate.confidence)
        assertEquals(BigDecimal("127.78"), candidate.deductibleAmount)
        assertEquals(CandidateStatus.PENDING, candidate.status)
        assertEquals(2024, candidate.taxYear)
        assertTrue(candidate.evidenceSnippets.contains("Office equipment"))
        verify { candidateRepo.insert(any()) }
    }

    @Test
    fun `classify returns empty list when LLM call fails`() {
        val extraction = InvoiceExtraction(
            id = UUID.randomUUID(),
            sourceDocumentId = UUID.randomUUID(),
            vendorName = "Unknown",
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

        every { retrieverService.retrieveTaxRules(any(), any()) } returns emptyList()
        every {
            ollamaClient.chat(
                model = props.chatModel,
                messages = any(),
                jsonFormat = true,
            )
        } throws OllamaException("LLM API error")

        val candidates = classificationService.classify(extraction)

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `classify parses JSON response correctly with partial confidence`() {
        val extraction = InvoiceExtraction(
            id = UUID.randomUUID(),
            sourceDocumentId = UUID.randomUUID(),
            vendorName = "Training Inc",
            invoiceNumber = "INV-2024",
            invoiceDate = LocalDate.of(2024, 3, 1),
            paymentDate = null,
            totalAmount = BigDecimal("500.00"),
            currency = "EUR",
            vatAmount = null,
            laborAmount = null,
            materialAmount = null,
            lineItems = emptyList(),
            rawLlmResponse = "{}",
            extractedAt = Instant.now(),
        )

        val llmResponse = """
            {
              "candidates": [
                {
                  "category": "koulutus",
                  "confidence": 0.42,
                  "deductibleAmount": 500,
                  "justification": "Professional training course",
                  "missingInformation": "Proof that training relates to current profession",
                  "suggestedNextAction": null,
                  "evidenceSnippets": ["Professional course"]
                }
              ]
            }
        """.trimIndent()

        every { retrieverService.retrieveTaxRules(any(), any()) } returns listOf(
            "Professional training is deductible if it relates to your profession."
        )
        every {
            ollamaClient.chat(
                model = props.chatModel,
                messages = any(),
                jsonFormat = true,
            )
        } returns llmResponse
        every { candidateRepo.insert(any()) } returns Unit

        val candidates = classificationService.classify(extraction)

        assertEquals(1, candidates.size)
        assertEquals("koulutus", candidates[0].category)
        assertEquals(0.42, candidates[0].confidence)
        // Confidence should be coerced to 0.0-1.0
        assertTrue(candidates[0].confidence in 0.0..1.0)
    }

    @Test
    fun `classify handles confidence edge cases`() {
        val extraction = InvoiceExtraction(
            id = UUID.randomUUID(),
            sourceDocumentId = UUID.randomUUID(),
            vendorName = "Test Vendor",
            invoiceNumber = null,
            invoiceDate = null,
            paymentDate = null,
            totalAmount = BigDecimal("100.00"),
            currency = "EUR",
            vatAmount = null,
            laborAmount = null,
            materialAmount = null,
            lineItems = emptyList(),
            rawLlmResponse = "{}",
            extractedAt = Instant.now(),
        )

        val llmResponse = """
            {
              "candidates": [
                {
                  "category": "tyovaline",
                  "confidence": 1.5,
                  "deductibleAmount": 50,
                  "justification": "Tools for work",
                  "missingInformation": null,
                  "suggestedNextAction": null,
                  "evidenceSnippets": []
                },
                {
                  "category": "polttoaine",
                  "confidence": -0.5,
                  "deductibleAmount": 25,
                  "justification": "Fuel expenses",
                  "missingInformation": null,
                  "suggestedNextAction": null,
                  "evidenceSnippets": []
                }
              ]
            }
        """.trimIndent()

        every { retrieverService.retrieveTaxRules(any(), any()) } returns emptyList()
        every {
            ollamaClient.chat(
                model = props.chatModel,
                messages = any(),
                jsonFormat = true,
            )
        } returns llmResponse
        every { candidateRepo.insert(any()) } returns Unit

        val candidates = classificationService.classify(extraction)

        assertEquals(2, candidates.size)
        // Over 1.0 should be clamped to 1.0
        assertEquals(1.0, candidates[0].confidence)
        // Negative should be clamped to 0.0
        assertEquals(0.0, candidates[1].confidence)
    }

    @Test
    fun `classify ignores malformed JSON gracefully`() {
        val extraction = InvoiceExtraction(
            id = UUID.randomUUID(),
            sourceDocumentId = UUID.randomUUID(),
            vendorName = "Vendor",
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

        every { retrieverService.retrieveTaxRules(any(), any()) } returns emptyList()
        every {
            ollamaClient.chat(
                model = props.chatModel,
                messages = any(),
                jsonFormat = true,
            )
        } returns "{ invalid json"

        val candidates = classificationService.classify(extraction)

        // Should return empty list, not crash
        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `classify retries once when first JSON is malformed and then succeeds`() {
        val extraction = InvoiceExtraction(
            id = UUID.randomUUID(),
            sourceDocumentId = UUID.randomUUID(),
            vendorName = "Vendor",
            invoiceNumber = null,
            invoiceDate = null,
            paymentDate = null,
            totalAmount = BigDecimal("120.00"),
            currency = "EUR",
            vatAmount = null,
            laborAmount = null,
            materialAmount = null,
            lineItems = emptyList(),
            rawLlmResponse = "{}",
            extractedAt = Instant.now(),
        )

        val validJson = """
            {
              "candidates": [
                {
                  "category": "tyovaline",
                  "confidence": 0.7,
                  "deductibleAmount": 120,
                  "justification": "Work equipment purchase",
                  "missingInformation": null,
                  "suggestedNextAction": null,
                  "evidenceSnippets": ["equipment"]
                }
              ]
            }
        """.trimIndent()

        every { retrieverService.retrieveTaxRules(any(), any()) } returns emptyList()
        every {
            ollamaClient.chat(
                model = props.chatModel,
                messages = any(),
                jsonFormat = true,
            )
        } returnsMany listOf("{ invalid json", validJson)
        every { candidateRepo.insert(any()) } returns Unit

        val candidates = classificationService.classify(extraction)

        assertEquals(1, candidates.size)
        assertEquals("tyovaline", candidates[0].category)
        verify(exactly = 2) {
            ollamaClient.chat(
                model = props.chatModel,
                messages = any(),
                jsonFormat = true,
            )
        }
        verify(exactly = 1) { candidateRepo.insert(any()) }
    }
}

