package stove.quarkus.example.api

import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import stove.quarkus.example.application.ProductCreateRequest
import stove.quarkus.example.application.ProductCreator

@Path("/api")
@Produces(MediaType.TEXT_PLAIN)
class ProductResource(
  private val productCreator: ProductCreator
) {
  @GET
  @Path("/index")
  fun index(
    @QueryParam("keyword") keyword: String?
  ): String = "Hi from Stove Quarkus example with $keyword"

  @POST
  @Path("/product/create")
  @Consumes(MediaType.APPLICATION_JSON)
  fun createProduct(request: ProductCreateRequest): String = productCreator.create(request)
}
