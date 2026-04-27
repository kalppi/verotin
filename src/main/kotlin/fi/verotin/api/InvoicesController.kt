package fi.verotin.api
import fi.verotin.domain.InvoiceExtraction
import fi.verotin.repository.InvoiceExtractionRepository
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID
@RestController
@RequestMapping("/api/extractions")
class InvoicesController(
    private val extractionRepo: InvoiceExtractionRepository,
) {
    @GetMapping
    fun list(): List<InvoiceExtraction> {
        return extractionRepo.findAll()
    }
    @GetMapping("/{id}")
    fun getById(@PathVariable id: UUID): InvoiceExtraction? {
        return extractionRepo.findById(id)
    }
    @GetMapping("/source/{sourceDocId}")
    fun getBySourceDocument(@PathVariable sourceDocId: UUID): InvoiceExtraction? {
        return extractionRepo.findBySourceDocumentId(sourceDocId)
    }
    @GetMapping("/unclassified")
    fun getUnclassified(): List<InvoiceExtraction> {
        return extractionRepo.findWithoutDeductionCandidates()
    }
}
