package stove.spring.example.infrastructure.couchbase

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "couchbase")
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
