package stove.ktor.example.app

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.get
import stove.ktor.example.application.*

fun Application.configureRouting() {
  routing {
    post("/products/{id}") {
      try {
        val id = call.parameters["id"]!!.toInt()
        val request = call.receive<UpdateProductRequest>()
        call.get<ProductService>().update(id, request)
        call.respond(HttpStatusCode.OK)
      } catch (
        @Suppress("TooGenericExceptionCaught") ex: Exception
      ) {
        ex.printStackTrace()
        call.respond(HttpStatusCode.BadRequest)
      }
    }
  }
}
