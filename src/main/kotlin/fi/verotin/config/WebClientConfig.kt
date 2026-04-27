package fi.verotin.config
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.client.WebClient
@Configuration
@EnableConfigurationProperties(OllamaProperties::class)
class WebClientConfig {
    @Bean
    fun ollamaWebClient(props: OllamaProperties): WebClient =
        WebClient.builder()
            .baseUrl(props.baseUrl)
            // No hard timeout here; embedding large batches can be slow.
            // Call-site code should apply its own deadline if needed.
            .build()
}
