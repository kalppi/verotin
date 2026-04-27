package fi.verotin.service
import fi.verotin.domain.SourceDocument
import fi.verotin.repository.SourceDocumentRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
/**
 * Orchestrates import of a raw document.
 *
 * Pipeline:
 * 1. Hash raw bytes for deduplication.
 * 2. Persist SourceDocument (idempotent via sha256_hash uniqueness).
 * 3. Chunk the document.
 * 4. Embed and store chunks.
 * 5. Extract structured invoice fields.
 *
 * Returns null if the document was already processed (duplicate).
 */
@Service
class IngestService(
    private val sourceDocumentRepo: SourceDocumentRepository,
    private val chunkingService: ChunkingService,
    private val embeddingService: EmbeddingService,
    private val extractionService: ExtractionService,
) {
    private val log = LoggerFactory.getLogger(IngestService::class.java)
    fun ingest(
        rawBytes: ByteArray,
        filename: String,
        contentType: String,
        receivedAt: Instant? = null,
    ): IngestResult? {
        val hash = sha256Hex(rawBytes)
        if (sourceDocumentRepo.existsByHash(hash)) {
            log.info("Skipping duplicate document: $filename (hash=$hash)")
            return null
        }
        val rawContent = textContent(rawBytes, contentType, filename)
        val doc = SourceDocument(
            id = UUID.randomUUID(),
            filename = filename,
            contentType = contentType,
            rawContent = rawContent,
            sha256Hash = hash,
            receivedAt = receivedAt,
            createdAt = Instant.now(),
        )
        sourceDocumentRepo.insert(doc)
        log.info("Persisted SourceDocument id=${doc.id} filename=$filename")
        val chunks = chunkingService.chunkDocument(doc.id, rawContent)
        val storedChunks = embeddingService.embedAndStoreDocumentChunks(chunks)
        val extraction = extractionService.extract(doc)
        log.info("Extracted invoice from ${doc.filename}: vendor=${extraction.vendorName}")
        return IngestResult(document = doc, chunkCount = storedChunks.size, extraction = extraction)
    }
    /**
     * Derive plain text from raw bytes based on content type.
     * For MVP: emails and text files are UTF-8 decoded directly.
     * PDFs use PDFBox (called by a dedicated parser).
     */
    private fun textContent(bytes: ByteArray, contentType: String, filename: String): String {
        return if (contentType == "pdf" || filename.endsWith(".pdf", ignoreCase = true)) {
            parsePdf(bytes)
        } else {
            bytes.toString(Charsets.UTF_8)
        }
    }
    private fun parsePdf(bytes: ByteArray): String {
        return try {
            org.apache.pdfbox.pdmodel.PDDocument.load(bytes).use { doc ->
                org.apache.pdfbox.text.PDFTextStripper().getText(doc)
            }
        } catch (ex: Exception) {
            log.warn("PDF text extraction failed, storing empty content", ex)
            ""
        }
    }
    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
data class IngestResult(
    val document: SourceDocument,
    val chunkCount: Int,
    val extraction: fi.verotin.domain.InvoiceExtraction,
)
