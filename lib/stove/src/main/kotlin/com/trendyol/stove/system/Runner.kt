package com.trendyol.stove.system

/**
 * Alias for runner of system under test
 */
typealias Runner<TContext> = (Array<String>) -> TContext
