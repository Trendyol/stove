package stove.micronaut.example.infrastructure.couchbase

import io.micronaut.context.annotation.*

@ConfigurationProperties("couchbase")
class CouchbaseProperties {
  var username: String? = null
  var password: String? = null
  var bucketName: String = ""
  var hosts: List<String> = listOf()
  var kvTimeout: Long = 0
  var connectTimeout: Long = 0
  var queryTimeout: Long = 0
  var viewTimeout: Long = 0
}
