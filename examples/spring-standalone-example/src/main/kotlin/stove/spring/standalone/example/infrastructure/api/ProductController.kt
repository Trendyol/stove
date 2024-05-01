package stove.spring.standalone.example.infrastructure.api

import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.mono
import org.springframework.http.codec.multipart.FilePart
import org.springframework.web.bind.annotation.*
import stove.spring.standalone.example.application.handlers.*

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
  ): String = productCreator.create(productCreateRequest)

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
