package fi.verotin.api
import fi.verotin.domain.CandidateStatus
import fi.verotin.domain.DeductionCandidate
import fi.verotin.repository.DeductionCandidateRepository
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID
@RestController
@RequestMapping("/api/deductions")
class DeductionsController(
    private val candidateRepo: DeductionCandidateRepository,
) {
    @GetMapping
    fun list(): List<DeductionCandidate> {
        return candidateRepo.findAll()
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
