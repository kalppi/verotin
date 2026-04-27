package fi.verotin.config
import com.fasterxml.jackson.databind.ObjectMapper
import org.postgresql.util.PGobject
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.PlatformTransactionManager
import javax.sql.DataSource
@Configuration
class DatabaseConfig {
    /**
     * Register the pgvector JDBC type so that org.postgresql can serialise
     * and deserialise vector(N) columns.  We do this once at startup by
     * calling the pgvector library's connection setup method.
     */
    @Bean
    fun pgvectorTypeRegistrar(dataSource: DataSource): PgvectorTypeRegistrar =
        PgvectorTypeRegistrar(dataSource)
    @Bean
    fun transactionManager(dataSource: DataSource): PlatformTransactionManager =
        DataSourceTransactionManager(dataSource)
}
/** Registers `vector` type with every connection obtained from the pool. */
class PgvectorTypeRegistrar(private val dataSource: DataSource) {
    init {
        dataSource.connection.use { conn ->
            com.pgvector.PGvector.addVectorType(conn)
        }
    }
}
