package com.trendyol.stove.testing.e2e.couchbase

import com.couchbase.client.java.ReactiveCluster
import com.couchbase.client.java.json.JsonObject
import com.couchbase.client.java.query.QueryOptions
import com.couchbase.client.java.query.QueryProfile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow

object ClusterExtensions {

    fun QueryOptions.withParams(vararg params: Pair<String, Any>): QueryOptions {
        val jObject = JsonObject.create()
        params.forEach { jObject.put(it.first, it.second) }
        this.parameters(jObject)
        return this
    }

    suspend inline fun <reified T> ReactiveCluster.executeQueryAs(
        query: String,
        queryOptionsConfigurer: (QueryOptions) -> Unit = {}
    ): List<T> {
        val options = createDefaultQueryOptions()
        queryOptionsConfigurer(options)
        return this.query(query, options).flatMapMany { it.rowsAs(T::class.java) }.asFlow().toList()
    }

    fun createDefaultQueryOptions(): QueryOptions {
        return QueryOptions.queryOptions().profile(QueryProfile.OFF).readonly(true)
    }
}
