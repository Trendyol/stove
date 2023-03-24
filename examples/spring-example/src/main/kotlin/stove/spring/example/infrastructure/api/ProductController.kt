package stove.spring.example.infrastructure.api

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import stove.spring.example.application.handlers.ProductCreateRequest
import stove.spring.example.application.handlers.ProductCreator

@RestController
@RequestMapping("/api")
class ProductController(private val productCreator: ProductCreator) {

    @GetMapping("/index")
    suspend fun get(@RequestParam(required = false) keyword: String): String {
        return "Hi from Stove framework with $keyword"
    }

    @PostMapping("/product/create")
    suspend fun createProduct(@RequestBody productCreateRequest: ProductCreateRequest): String {
        return productCreator.createNewProduct(productCreateRequest)
    }
}
