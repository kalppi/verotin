package fi.verotin.api
import fi.verotin.service.RetrieverService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
@RestController
@RequestMapping("/api/search")
class RetrievalController(
    private val retrieverService: RetrieverService,
) {
    @GetMapping("/documents")
    fun searchDocuments(
        @RequestParam query: String,
        @RequestParam(defaultValue = "5") topK: Int,
    ): Map<String, Any> {
        val results = retrieverService.retrieveDocumentChunks(query, topK)
        return mapOf(
            "query" to query,
            "topK" to topK,
            "results" to results,
        )
    }
    @GetMapping("/tax-rules")
    fun searchTaxRules(
        @RequestParam query: String,
        @RequestParam(defaultValue = "5") topK: Int,
    ): Map<String, Any> {
        val results = retrieverService.retrieveTaxRules(query, topK)
        return mapOf(
            "query" to query,
            "topK" to topK,
            "results" to results,
        )
    }
}
