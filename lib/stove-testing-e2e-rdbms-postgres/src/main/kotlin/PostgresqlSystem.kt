import arrow.core.getOrElse
import com.trendyol.stove.testing.e2e.containers.DEFAULT_REGISTRY
import com.trendyol.stove.testing.e2e.containers.withProvidedRegistry
import com.trendyol.stove.testing.e2e.rdbms.RelationalDatabaseContext
import com.trendyol.stove.testing.e2e.rdbms.RelationalDatabaseExposedConfiguration
import com.trendyol.stove.testing.e2e.rdbms.RelationalDatabaseSystem
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.abstractions.SystemNotRegisteredException
import io.r2dbc.postgresql.PostgresqlConnectionConfiguration
import io.r2dbc.postgresql.PostgresqlConnectionFactory
import io.r2dbc.spi.ConnectionFactory
import org.testcontainers.containers.PostgreSQLContainer

internal class PostgresqlContext(
    container: PostgreSQLContainer<*>,
    configureExposedConfiguration: (RelationalDatabaseExposedConfiguration) -> List<String>,
) : RelationalDatabaseContext<PostgreSQLContainer<*>>(container, configureExposedConfiguration)

class PostgresqlSystem internal constructor(
    testSystem: TestSystem,
    context: PostgresqlContext,
) : RelationalDatabaseSystem<PostgresqlSystem>(testSystem, context) {
    override val connectionFactory: ConnectionFactory
        get() {
            val builder = PostgresqlConnectionConfiguration.builder().apply {
                host(context.container.host)
                database(context.container.databaseName)
                port(context.container.firstMappedPort)
                password(context.container.password)
                username(context.container.username)
            }
            return PostgresqlConnectionFactory(builder.build())
        }
}

const val DEFAULT_POSTGRES_IMAGE_NAME = "postgres"

fun TestSystem.withPostgresql(
    configureExposedConfiguration: (RelationalDatabaseExposedConfiguration) -> List<String> = { _ ->
        listOf()
    },
): TestSystem = this.withPostgresql(registry = null, imageName = null, compatibleSubstitute = null, configureExposedConfiguration)

fun TestSystem.withPostgresql(
    registry: String? = DEFAULT_REGISTRY,
    imageName: String? = DEFAULT_POSTGRES_IMAGE_NAME,
    compatibleSubstitute: String? = null,
    configureExposedConfiguration: (RelationalDatabaseExposedConfiguration) -> List<String> = { _ ->
        listOf()
    },
): TestSystem {
    val container = if (registry == null) {
        PostgreSQLContainer(DEFAULT_POSTGRES_IMAGE_NAME).withDatabaseName("integration-tests-db").withUsername("sa").withPassword("sa")
    } else {
        withProvidedRegistry(imageName ?: DEFAULT_POSTGRES_IMAGE_NAME, registry, compatibleSubstitute) {
            PostgreSQLContainer(it).withDatabaseName("integration-tests-db").withUsername("sa").withPassword("sa")
        }
    }
    this.getOrRegister(
        PostgresqlSystem(
            this,
            PostgresqlContext(container, configureExposedConfiguration)
        )
    )
    return this
}

fun TestSystem.postgresql(): PostgresqlSystem = getOrNone<PostgresqlSystem>().getOrElse {
    throw SystemNotRegisteredException(PostgresqlSystem::class)
}
