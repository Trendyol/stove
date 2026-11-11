package stove.cucumber.example

import com.trendyol.stove.http.HttpClientSystemOptions
import com.trendyol.stove.http.httpClient
import com.trendyol.stove.ktor.ktor
import com.trendyol.stove.system.PortFinder
import com.trendyol.stove.system.Stove
import io.cucumber.java.AfterAll
import io.cucumber.java.BeforeAll
import kotlinx.coroutines.runBlocking

object InventoryEnvironment {
  @JvmStatic
  @BeforeAll
  fun start(): Unit = runBlocking {
    val port = PortFinder.findAvailablePort()
    Stove().with {
      httpClient { HttpClientSystemOptions(baseUrl = "http://127.0.0.1:$port") }
      ktor(runner = { startInventoryApplication(port) })
    }.run()
  }

  @JvmStatic
  @AfterAll
  fun stop() = Stove.stop()
}
