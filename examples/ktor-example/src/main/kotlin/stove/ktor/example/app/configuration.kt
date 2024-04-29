package stove.ktor.example.app

import com.sksamuel.hoplite.*
import com.sksamuel.hoplite.env.Environment

@OptIn(ExperimentalHoplite::class)
inline fun <reified T : Any> loadConfiguration(args: Array<String> = arrayOf()): T = ConfigLoaderBuilder.default()
  .addEnvironmentSource()
  .addCommandLineSource(args)
  .withExplicitSealedTypes()
  .withEnvironment(AppEnv.toEnv())
  .apply {
    when (AppEnv.current()) {
      AppEnv.Local -> {
        addResourceSource("/application.yaml", optional = true)
      }

      AppEnv.Prod -> {
        addResourceSource("/application-prod.yaml", optional = true)
        addResourceSource("/application.yaml", optional = true)
      }

      else -> {
        addResourceSource("/application.yaml", optional = true)
      }
    }
  }
  .build()
  .loadConfigOrThrow<T>()

data class AppConfiguration(
  val port: Int,
  val database: DatabaseConfiguration,
  val kafka: KafkaConfiguration
)

data class DatabaseConfiguration(
  val host: String,
  val port: Int,
  val name: String,
  val jdbcUrl: String,
  val username: String,
  val password: String
)

data class KafkaConfiguration(
  val bootstrapServers: String,
  val groupId: String,
  val clientId: String,
  val interceptorClasses: List<String>,
  val topics: Map<String, TopicConfiguration>
)

data class TopicConfiguration(
  val topic: String,
  val retry: String,
  val error: String
)

enum class AppEnv(val env: String) {
  Unspecified(""),
  Local(Environment.local.name),
  Prod(Environment.prod.name)
  ;

  companion object {
    fun current(): AppEnv = when (System.getenv("ENVIRONMENT")) {
      Unspecified.env -> Unspecified
      Local.env -> Local
      Prod.env -> Prod
      else -> Local
    }

    fun toEnv(): Environment = when (current()) {
      Local -> Environment.local
      Prod -> Environment.prod
      else -> Environment.local
    }
  }

  fun isLocal(): Boolean {
    return this === Local
  }

  fun isProd(): Boolean {
    return this === Prod
  }
}
