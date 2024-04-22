package stove.spring.example.infrastructure.api

import kotlinx.coroutines.reactive.*
import kotlinx.coroutines.reactor.mono
import org.springframework.http.codec.multipart.FilePart
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import stove.spring.example.application.handlers.ProductCreateRequest
import stove.spring.example.application.handlers.ProductCreator

@RestController
@RequestMapping("/api")
class ProductController(private val productCreator: ProductCreator) {
    @GetMapping("/index")
    suspend fun get(
        @RequestParam(required = false) keyword: String
    ): String {
        return "Hi from Stove framework with $keyword"
    }

    @PostMapping("/product/create")
    suspend fun createProduct(
        @RequestBody productCreateRequest: ProductCreateRequest
    ): String {
        return productCreator.create(productCreateRequest)
    }

    @PostMapping("/product/import")
    suspend fun importFile(
        @RequestPart(name = "name") name: String,
        @RequestPart(name = "file") file: FilePart
    ): String {
        val content = file.content()
            .flatMap { mono { it.asInputStream().readAllBytes() } }
            .awaitSingle()
            .let { String(it) }
        return "File ${file.filename()} is imported with $name and content: $content"
    }
}
