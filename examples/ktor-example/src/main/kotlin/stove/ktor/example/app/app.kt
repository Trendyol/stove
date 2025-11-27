package stove.ktor.example.app

import com.fasterxml.jackson.databind.json.JsonMapper
import org.koin.dsl.*
import stove.ktor.example.application.*
import stove.ktor.example.domain.ProductRepository

val objectMapperRef: JsonMapper = JsonMapper
  .builder()
  .apply { this.findAndAddModules() }
  .build()

fun app() = module {
  single { ProductRepository(get()) }
  single { ProductService(get(), get(), get()) }
  single { MutexLockProvider() }.bind<LockProvider>()
}
