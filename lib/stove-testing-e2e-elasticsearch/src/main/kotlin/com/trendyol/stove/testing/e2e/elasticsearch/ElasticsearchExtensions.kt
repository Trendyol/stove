package com.trendyol.stove.testing.e2e.elasticsearch

import co.elastic.clients.elasticsearch._types.query_dsl.QueryVariant

fun QueryVariant.asJsonString(): String = this._toQuery().toString().removePrefix("Query:")
