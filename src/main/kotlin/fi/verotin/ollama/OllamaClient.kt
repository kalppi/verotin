package fi.verotin.ollama
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.bodyToMono
/**
 * Thin wrapper around the Ollama HTTP API.
 * Both methods block until the response arrives (MVP: synchronous pipeline).
 *
 * Error handling: network/HTTP errors are wrapped in OllamaException so callers
 * can decide to retry, skip, or surface the error without depending on HTTP details.
 */
@Component
class OllamaClient(private val webClient: WebClient) {
    private val log = LoggerFactory.getLogger(OllamaClient::class.java)
    /**
     * Generate an embedding vector for [input] using [model].
     * Returns a FloatArray matching the model's output dimension.
     * @throws OllamaException on HTTP or network failure.
     */
    fun embed(model: String, input: String): FloatArray {
        val request = OllamaEmbedRequest(model = model, input = input)
        return try {
            val response = webClient
                .post()
                .uri("/api/embed")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono<OllamaEmbedResponse>()
                .block()
                ?: throw OllamaException("Ollama /api/embed returned null body")
            if (response.embeddings.isEmpty()) {
                throw OllamaException("Ollama returned empty embeddings list for model $model")
            }
            response.embeddings[0].map { it.toFloat() }.toFloatArray()
        } catch (ex: WebClientResponseException) {
            val a = ex.message
            val b = ex.responseBodyAsString
            log.error("Ollama embed HTTP ${ex.statusCode}: ${ex.responseBodyAsString}")
            throw OllamaException("Ollama embed failed: ${ex.statusCode}", ex)
        } catch (ex: OllamaException) {
            throw ex
        } catch (ex: Exception) {
            log.error("Ollama embed unexpected error", ex)
            throw OllamaException("Ollama embed unexpected error: ${ex.message}", ex)
        }
    }
    /**
     * Send a chat completion request and return the assistant message content.
     * Set [jsonFormat] = true to request JSON output from the model.
     * @throws OllamaException on HTTP or network failure.
     */
    fun chat(
        model: String,
        messages: List<OllamaMessage>,
        jsonFormat: Boolean = false,
    ): String {
        val request = OllamaChatRequest(
            model = model,
            messages = messages,
            stream = false,
            format = if (jsonFormat) "json" else null,
        )
        return try {
            val response = webClient
                .post()
                .uri("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono<OllamaChatResponse>()
                .block()
                ?: throw OllamaException("Ollama /api/chat returned null body")
            response.message.content
        } catch (ex: WebClientResponseException) {
            log.error("Ollama chat HTTP ${ex.statusCode}: ${ex.responseBodyAsString}")
            throw OllamaException("Ollama chat failed: ${ex.statusCode}", ex)
        } catch (ex: OllamaException) {
            throw ex
        } catch (ex: Exception) {
            log.error("Ollama chat unexpected error", ex)
            throw OllamaException("Ollama chat unexpected error: ${ex.message}", ex)
        }
    }
}
class OllamaException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
