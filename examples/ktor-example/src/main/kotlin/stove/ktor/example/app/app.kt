package stove.ktor.example.app

import org.koin.dsl.*
import stove.ktor.example.application.*
import stove.ktor.example.domain.ProductRepository

fun app() = module {
  single { ProductRepository(get()) }
  single { ProductService(get(), get()) }
  single { MutexLockProvider() }.bind<LockProvider>()
}
