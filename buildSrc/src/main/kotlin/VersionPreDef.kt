fun nextPatchSnapshot(version: String): String {
  val (major, minor, patch) = version.split(".")
  return "$major.$minor.${patch.toInt() + 1}-SNAPSHOT"
}
