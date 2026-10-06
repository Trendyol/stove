package stove.ktor.bff.web

import io.ktor.http.ContentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.*

internal fun Route.webRoutes() {
  get("/health") { call.respondText("ready") }
  get("/") { call.respondText(WebAssets.read("index.html"), ContentType.Text.Html) }
  get("/app.js") { call.respondText(WebAssets.read("app.js"), ContentType.Application.JavaScript) }
  get("/style.css") { call.respondText(WebAssets.read("style.css"), ContentType.Text.CSS) }
}

private object WebAssets {
  fun read(name: String): String = checkNotNull(javaClass.getResource("/web/$name")) { "Missing web/$name" }.readText()
}
