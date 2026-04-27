package fi.verotin.api
import fi.verotin.domain.DocumentChunk
import fi.verotin.domain.SourceDocument
import fi.verotin.repository.DocumentChunkRepository
import fi.verotin.repository.SourceDocumentRepository
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID
@RestController
@RequestMapping("/api/documents")
class DocumentsController(
    private val sourceDocumentRepo: SourceDocumentRepository,
    private val documentChunkRepo: DocumentChunkRepository,
) {
    @GetMapping
    fun list(): List<SourceDocument> {
        return sourceDocumentRepo.findAll()
    }
    @GetMapping("/{id}")
    fun getById(@PathVariable id: UUID): SourceDocument? {
        return sourceDocumentRepo.findById(id)
    }
    @GetMapping("/{id}/chunks")
    fun getChunks(@PathVariable id: UUID): List<DocumentChunk> {
        return documentChunkRepo.findBySourceDocumentId(id)
    }
}
