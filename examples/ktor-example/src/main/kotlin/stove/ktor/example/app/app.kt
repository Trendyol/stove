package stove.ktor.example.app

import com.fasterxml.jackson.databind.ObjectMapper
import org.koin.dsl.*
import stove.ktor.example.application.*
import stove.ktor.example.domain.ProductRepository

val objectMapperRef: ObjectMapper = ObjectMapper().apply {
  findAndRegisterModules()
}

fun app() = module {
  single { ProductRepository(get()) }
  single { ProductService(get(), get(), get()) }
  single { MutexLockProvider() }.bind<LockProvider>()
}
