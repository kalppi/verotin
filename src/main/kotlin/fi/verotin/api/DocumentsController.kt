package fi.verotin.api
import fi.verotin.domain.DocumentChunk
import fi.verotin.domain.SourceDocument
import fi.verotin.repository.DocumentChunkRepository
import fi.verotin.repository.SourceDocumentRepository
import fi.verotin.service.IngestResult
import fi.verotin.service.IngestService
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@RestController
@RequestMapping("/api/documents")
class DocumentsController(
    private val sourceDocumentRepo: SourceDocumentRepository,
    private val documentChunkRepo: DocumentChunkRepository,
    private val ingestService: IngestService,
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

    @PostMapping("/upload", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    fun upload(@RequestParam("file") file: MultipartFile): IngestResult {
        if (file.isEmpty) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "File is empty")
        val contentType = when {
            file.originalFilename?.endsWith(".pdf", ignoreCase = true) == true -> "pdf"
            file.contentType?.contains("pdf") == true -> "pdf"
            file.originalFilename?.endsWith(".eml", ignoreCase = true) == true -> "email"
            file.contentType?.contains("message/rfc822") == true -> "email"
            else -> "txt"
        }
        return ingestService.ingest(
            rawBytes = file.bytes,
            filename = file.originalFilename ?: file.name,
            contentType = contentType,
        ) ?: throw ResponseStatusException(HttpStatus.CONFLICT, "Duplicate document already ingested")
    }
}
