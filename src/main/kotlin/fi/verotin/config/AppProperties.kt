package fi.verotin.config
import org.springframework.boot.context.properties.ConfigurationProperties
@ConfigurationProperties(prefix = "verotin.fixtures")
data class AppProperties(
    val ingestOnStartup: Boolean = false,
)
