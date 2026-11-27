package com.trendyol.stove.gradle.openapi

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory

// Own file: Jackson exists only on the worker classpath, so task code must never load this class.
// Configured once and never changed, so one instance is safe to share across workers.
internal val json = ObjectMapper()
internal val yaml = ObjectMapper(YAMLFactory())
