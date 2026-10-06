package stove.ktor.bff.auth.storage.postgres

import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.JavaInstantColumnType
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.json.jsonb
import java.time.Instant

internal object StoreNow : CustomFunction<Instant>("clock_timestamp", JavaInstantColumnType())

internal object LoginAttempts : Table("bff_login_attempts") {
  val namespace = text("namespace")
  val id = text("id")
  val payload = jsonb("payload", sessionJson, LoginDocument.serializer())
  val expiresAt = timestamp("expires_at")
  override val primaryKey = PrimaryKey(namespace, id)
}

internal object Sessions : Table("bff_sessions") {
  val namespace = text("namespace")
  val id = text("id")
  val version = long("version")
  val payload = jsonb("payload", sessionJson, SessionDocument.serializer())
  val expiresAt = timestamp("expires_at")
  override val primaryKey = PrimaryKey(namespace, id)
}
