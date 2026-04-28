package fi.verotin
import fi.verotin.config.AppProperties
import fi.verotin.config.OllamaProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
@SpringBootApplication
@EnableConfigurationProperties(AppProperties::class, OllamaProperties::class)
class VerotinApplication
fun main(args: Array<String>) {
    runApplication<VerotinApplication>(*args)
}
