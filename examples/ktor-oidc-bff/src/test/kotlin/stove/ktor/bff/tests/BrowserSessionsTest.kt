package stove.ktor.bff.tests

import io.kotest.core.spec.style.FunSpec
import stove.ktor.bff.fixtures.*

class BrowserSessionsTest : FunSpec({ sessionStorageContract { SessionStores.inMemory() } })

class PostgresBrowserSessionsTest : FunSpec({ sessionStorageContract { SessionStores.postgres() } })
