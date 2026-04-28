package fi.verotin.api

import fi.verotin.domain.CandidateStatus
import fi.verotin.domain.DeductionCandidate
import fi.verotin.repository.DeductionCandidateRepository
import fi.verotin.repository.InvoiceExtractionRepository
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/api/deductions")
class DeductionsController(
    private val candidateRepo: DeductionCandidateRepository,
    private val extractionRepo: InvoiceExtractionRepository,
) {
    @GetMapping
    fun list(): List<DeductionInvoiceDto> {
        return candidateRepo.findAll()
            .groupBy { it.invoiceExtractionId }
            .map { (extractionId, candidates) ->
                val extraction = extractionRepo.findById(extractionId)
                DeductionInvoiceDto(
                    invoiceExtractionId = extractionId,
                    vendorName = extraction?.vendorName,
                    invoiceNumber = extraction?.invoiceNumber,
                    invoiceDate = extraction?.invoiceDate,
                    currency = extraction?.currency,
                    totalInvoiceSum = extraction?.totalAmount,
                    actualDeductibleValue = candidates
                        .mapNotNull { it.deductibleAmount }
                        .fold(BigDecimal.ZERO, BigDecimal::add),
                    candidates = candidates,
                )
            }
            .sortedByDescending { it.invoiceDate ?: LocalDate.MIN }
    }

    @GetMapping("/{id}")
    fun getById(@PathVariable id: UUID): DeductionCandidate? {
        return candidateRepo.findAll().find { it.id == id }
    }

    @GetMapping("/extraction/{extractionId}")
    fun getByExtraction(@PathVariable extractionId: UUID): List<DeductionCandidate> {
        return candidateRepo.findByExtractionId(extractionId)
    }

    @PostMapping("/{id}/accept")
    fun accept(@PathVariable id: UUID) {
        candidateRepo.updateStatus(id, CandidateStatus.ACCEPTED)
    }

    @PostMapping("/{id}/reject")
    fun reject(@PathVariable id: UUID) {
        candidateRepo.updateStatus(id, CandidateStatus.REJECTED)
    }

    @GetMapping("/pending")
    fun getPending(): List<DeductionCandidate> {
        return candidateRepo.findAll().filter { it.status == CandidateStatus.PENDING }
    }

    @GetMapping("/category/{category}")
    fun byCategory(@PathVariable category: String): List<DeductionCandidate> {
        return candidateRepo.findAll().filter { it.category == category }
    }
}

data class DeductionInvoiceDto(
    val invoiceExtractionId: UUID,
    val vendorName: String?,
    val invoiceNumber: String?,
    val invoiceDate: LocalDate?,
    val currency: String?,
    val totalInvoiceSum: BigDecimal?,
    val actualDeductibleValue: BigDecimal,
    val candidates: List<DeductionCandidate>,
)
