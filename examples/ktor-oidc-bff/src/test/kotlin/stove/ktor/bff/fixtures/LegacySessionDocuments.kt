package stove.ktor.bff.fixtures

/** Version-one JSON written by the original handwritten codec, kept independent of current serializers. */
object LegacySessionDocuments {
  fun read(name: String): String = checkNotNull(javaClass.getResource("/sessions/v1/$name.json")).readText()
}
