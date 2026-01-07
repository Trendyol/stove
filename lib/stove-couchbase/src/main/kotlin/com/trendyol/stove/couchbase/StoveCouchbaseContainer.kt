package com.trendyol.stove.couchbase

import com.trendyol.stove.containers.StoveContainer
import org.testcontainers.couchbase.CouchbaseContainer
import org.testcontainers.utility.DockerImageName

open class StoveCouchbaseContainer(
  override val imageNameAccess: DockerImageName
) : CouchbaseContainer(imageNameAccess),
  StoveContainer
