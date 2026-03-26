package com.trendyol.stove.portal

import com.trendyol.stove.system.abstractions.SystemOptions

/**
 * Configuration for the Portal system.
 *
 * @param appName Application name for grouping runs (e.g., "product-api").
 *   Required — identifies which application this test suite targets.
 * @param cliHost Hostname where the stove CLI is running.
 * @param cliPort gRPC port where the stove CLI is listening.
 */
data class PortalSystemOptions(
  val appName: String,
  val cliHost: String = "localhost",
  val cliPort: Int = 4041
) : SystemOptions
