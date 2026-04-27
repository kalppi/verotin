package fi.verotin.ollama
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
// ── Embed API ──────────────────────────────────────────────────────────────────
data class OllamaEmbedRequest(
    val model: String,
    val input: String,
)
@JsonIgnoreProperties(ignoreUnknown = true)
data class OllamaEmbedResponse(
    /** Ollama /api/embed returns a list of embedding arrays */
    val embeddings: List<List<Double>>,
)
// ── Chat API ───────────────────────────────────────────────────────────────────
data class OllamaChatRequest(
    val model: String,
    val messages: List<OllamaMessage>,
    val stream: Boolean = false,
    /** Ask Ollama to return structured JSON output */
    val format: String? = null,
)
data class OllamaMessage(
    val role: String,  // "system" | "user" | "assistant"
    val content: String,
)
@JsonIgnoreProperties(ignoreUnknown = true)
data class OllamaChatResponse(
    val message: OllamaMessage,
)
