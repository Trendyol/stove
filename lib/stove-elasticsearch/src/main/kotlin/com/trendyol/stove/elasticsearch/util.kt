package com.trendyol.stove.elasticsearch

import co.elastic.clients.elasticsearch._types.query_dsl.QueryVariant

internal fun QueryVariant.asJsonString(): String = this._toQuery().toString().removePrefix("Query:")
