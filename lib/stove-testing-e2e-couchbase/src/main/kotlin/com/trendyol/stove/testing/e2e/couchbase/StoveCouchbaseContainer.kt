package com.trendyol.stove.testing.e2e.couchbase

import com.trendyol.stove.testing.e2e.containers.StoveContainer
import org.testcontainers.couchbase.CouchbaseContainer
import org.testcontainers.utility.DockerImageName

open class StoveCouchbaseContainer(
  override val imageNameAccess: DockerImageName
) : CouchbaseContainer(imageNameAccess), StoveContainer
