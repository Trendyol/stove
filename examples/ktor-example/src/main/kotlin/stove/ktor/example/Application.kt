@file:Suppress("ExtractKtorModule")

package stove.ktor.example

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.config.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.r2dbc.postgresql.PostgresqlConnectionConfiguration
import io.r2dbc.postgresql.PostgresqlConnectionFactory
import kotlinx.serialization.Serializable
import org.koin.core.module.Module
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import org.koin.ktor.ext.get
import org.koin.ktor.plugin.Koin
import org.koin.logger.SLF4JLogger
import org.slf4j.event.*
import stove.ktor.example.domain.JediRepository
import stove.ktor.example.domain.JediService
import java.time.Duration

fun main(args: Array<String>) {
    run(args)
}

fun run(
    args: Array<String>,
    applicationOverrides: () -> Module = { module { } }
): ApplicationEngine {
    val applicationEngine =
        embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
            mainModule(args, applicationOverrides)
        }
    applicationEngine.start(wait = false)

    return applicationEngine
}

@Serializable
data class UpdateJediRequest(val name: String)

fun Application.mainModule(
    args: Array<String>,
    applicationOverrides: () -> Module
) {
    install(CallLogging) {
    }

    install(ContentNegotiation) {
        json()
    }

    install(Koin) {
        SLF4JLogger()
        modules(
            dataModule(args),
            applicationModule(),
            applicationOverrides()
        )
    }

    routing {
        post("/jedis/{id}") {
            val id = call.parameters["id"]!!.toLong()
            try {
                val request = call.receive<UpdateJediRequest>()
                call.get<JediService>().update(id, request)
                call.respond(HttpStatusCode.OK)
            } catch (ex: Exception) {
                ex.printStackTrace()
                call.respond(HttpStatusCode.BadRequest)
            }
        }
    }
}

fun dataModule(args: Array<String>) =
    module {
        val map = args.associate { it.split("=")[0] to it.split("=")[1] }
        single {
            val builder =
                PostgresqlConnectionConfiguration.builder().apply {
                    host(map["database.host"]!!)
                    database(map["database.databaseName"]!!)
                    port(map["database.port"]!!.toInt())
                    password(map["database.password"]!!)
                    username(map["database.username"]!!)
                }
            PostgresqlConnectionFactory(builder.connectTimeout(Duration.ofSeconds(10)).build())
        }
    }

fun applicationModule() =
    module {
        singleOf(::JediRepository)
        singleOf(::JediService)
        singleOf(::MutexLockProvider) { bind<LockProvider>() }
    }
