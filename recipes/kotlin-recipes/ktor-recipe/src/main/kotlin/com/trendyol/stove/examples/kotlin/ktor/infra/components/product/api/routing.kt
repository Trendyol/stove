package com.trendyol.stove.examples.kotlin.ktor.infra.components.product.api

import com.trendyol.kediatr.Mediator
import com.trendyol.stove.examples.kotlin.ktor.application.product.command.CreateProductCommand
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.get

fun Routing.productApi() {
  post("/products") {
    val mediator = call.get<Mediator>()
    val req = call.receive<ProductCreateRequest>()
    mediator.send(CreateProductCommand(req.name, req.price, req.categoryId))
    call.respond("Product created")
  }
}

data class ProductCreateRequest(var name: String, var price: Double, var categoryId: Int)
