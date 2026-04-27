package fi.verotin.config

import fi.verotin.ollama.OllamaClient
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.boot.actuate.health.Status
import org.springframework.stereotype.Component
import org.slf4j.LoggerFactory

/**
 * Health check for Ollama service.
 * Used with Spring Actuator at /actuator/health
 */
@Component("ollama")
class OllamaHealthIndicator(
    private val ollamaClient: OllamaClient,
    private val props: OllamaProperties,
) : HealthIndicator {
    private val log = LoggerFactory.getLogger(OllamaHealthIndicator::class.java)

    override fun health(): Health {
        return try {
            // Try a small embedding request to verify Ollama is responsive
            ollamaClient.embed(props.embedModel, "health check")
            Health.up()
                .withDetail("baseUrl", props.baseUrl)
                .withDetail("embedModel", props.embedModel)
                .withDetail("chatModel", props.chatModel)
                .build()
        } catch (ex: Exception) {
            log.warn("Ollama health check failed: ${ex.message}")
            Health.down()
                .withDetail("baseUrl", props.baseUrl)
                .withException(ex)
                .build()
        }
    }
}

