package fi.verotin.config
import org.springframework.boot.context.properties.ConfigurationProperties
@ConfigurationProperties(prefix = "ollama")
data class OllamaProperties(
    val baseUrl: String = "http://localhost:11434",
    val embedModel: String = "mxbai-embed-large",
    val chatModel: String = "llama3",
    val embeddingDimensions: Int = 1024,
)
