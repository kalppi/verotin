package fi.verotin.api

import fi.verotin.repository.DeductionCandidateRepository
import fi.verotin.repository.DocumentChunkRepository
import fi.verotin.repository.InvoiceExtractionRepository
import fi.verotin.repository.SourceDocumentRepository
import fi.verotin.repository.TaxRuleChunkRepository
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * System statistics and status endpoint.
 * Provides overview of indexed data and pipeline status.
 */
@RestController
@RequestMapping("/api/stats")
class StatsController(
    private val sourceDocumentRepo: SourceDocumentRepository,
    private val documentChunkRepo: DocumentChunkRepository,
    private val extractionRepo: InvoiceExtractionRepository,
    private val deductionRepo: DeductionCandidateRepository,
    private val taxRuleChunkRepo: TaxRuleChunkRepository,
) {

    @GetMapping
    fun getStats(): Map<String, Any> {
        val documents = sourceDocumentRepo.findAll()
        val chunks = documentChunkRepo.findAll()
        val extractions = extractionRepo.findAll()
        val deductions = deductionRepo.findAll()

        return mapOf(
            "timestamp" to Instant.now(),
            "documents" to mapOf(
                "total" to documents.size,
                "byContentType" to documents.groupingBy { it.contentType }.eachCount(),
            ),
            "chunks" to mapOf(
                "total" to chunks.size,
                "withEmbeddings" to chunks.count { it.embedding != null },
                "withoutEmbeddings" to chunks.count { it.embedding == null },
            ),
            "extractions" to mapOf(
                "total" to extractions.size,
                "withVendor" to extractions.count { !it.vendorName.isNullOrBlank() },
                "withAmount" to extractions.count { it.totalAmount != null },
            ),
            "deductions" to mapOf(
                "total" to deductions.size,
                "pending" to deductions.count { it.status.name == "PENDING" },
                "accepted" to deductions.count { it.status.name == "ACCEPTED" },
                "rejected" to deductions.count { it.status.name == "REJECTED" },
                "byCategory" to deductions.groupingBy { it.category }.eachCount(),
                "averageConfidence" to if (deductions.isNotEmpty()) {
                    deductions.map { it.confidence }.average()
                } else {
                    0.0
                },
            ),
            "taxRules" to mapOf(
                "total" to (taxRuleChunkRepo.findAll()?.size ?: 0),
            ),
        )
    }
}

