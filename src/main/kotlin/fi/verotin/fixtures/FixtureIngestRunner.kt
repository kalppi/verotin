package fi.verotin.fixtures
import fi.verotin.config.AppProperties
import fi.verotin.service.ChunkingService
import fi.verotin.service.DeductionClassificationService
import fi.verotin.service.EmbeddingService
import fi.verotin.service.IngestService
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.io.ResourceLoader
import org.springframework.stereotype.Component
/**
 * Loads fixture files from resources on application startup (if configured).
 * Processes email samples and tax rules through the full ingest and classification pipeline.
 *
 * Configured via verotin.fixtures.ingest-on-startup property.
 */
@Component
class FixtureIngestRunner(
    private val appProps: AppProperties,
    private val ingestService: IngestService,
    private val classificationService: DeductionClassificationService,
    private val chunkingService: ChunkingService,
    private val embeddingService: EmbeddingService,
    private val resourceLoader: ResourceLoader,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(FixtureIngestRunner::class.java)
    override fun run(args: ApplicationArguments?) {
        if (!appProps.ingestOnStartup) {
            log.debug("Fixture ingest disabled")
            return
        }
        log.info("Loading fixture files...")
        // Load and ingest tax rules
        ingestTaxRules()
        // Load and ingest sample documents
        ingestFixtures()
        log.info("Fixture ingest complete")
    }
    private fun ingestTaxRules() {
        val resource = resourceLoader.getResource("classpath:fixtures/tax_rules_fi_2024.txt")
        if (!resource.exists()) {
            log.warn("Tax rules file not found")
            return
        }
        val content = resource.inputStream.bufferedReader().use { it.readText() }
        if (content.isBlank()) {
            log.warn("Tax rules file is empty")
            return
        }
        val chunks = chunkingService.chunkTaxRules("TVL 2024", content)
        embeddingService.embedAndStoreTaxRuleChunks(chunks)
        log.info("Ingested ${chunks.size} tax rule chunks")
    }
    private fun ingestFixtures() {
        // Ingest fixtures using byte-accurate reads so binary files (e.g. PDFs) are not corrupted.
        listOf(/*"sample_invoice_1.eml", "sample_invoice_2.eml", */"Kuitti 128255003 - Verkkokauppa.com.pdf").forEach { filename ->
            try {
                val resource = resourceLoader.getResource("classpath:fixtures/$filename")
                if (!resource.exists()) {
                    log.debug("Fixture not found: $filename")
                    return@forEach
                }
                val bytes = resource.inputStream.use { it.readBytes() }
                val contentType = detectContentType(filename)
                val result = ingestService.ingest(bytes, filename, contentType)
                if (result != null) {
                    log.info("Ingested ${filename}: ${result.chunkCount} chunks")
                    // Classify for possible deductions
                    val candidates = classificationService.classify(result.extraction)
                    log.info("Classified into ${candidates.size} deduction candidates")
                } else {
                    log.info("Skipped duplicate: $filename")
                }
            } catch (ex: Exception) {
                log.error("Error ingesting $filename", ex)
            }
        }
    }

    private fun detectContentType(filename: String): String {
        return when {
            filename.endsWith(".pdf", ignoreCase = true) -> "pdf"
            filename.endsWith(".eml", ignoreCase = true) -> "email"
            else -> "txt"
        }
    }
}
