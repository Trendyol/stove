package com.trendyol.stove.rdbms

import com.trendyol.stove.ConcurrentProjectConfig

// Each test owns its Stove instance and a uniquely named in-memory database.
class KotestConfig : ConcurrentProjectConfig(concurrentTests = true)
