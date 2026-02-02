plugins {
  `java-platform`
  alias(libs.plugins.maven.publish)
}

javaPlatform {
  allowDependencies()
}

dependencies {
  constraints {
    // Core
    api(projects.lib.stove)

    // Infrastructure
    api(projects.lib.stoveCouchbase)
    api(projects.lib.stoveElasticsearch)
    api(projects.lib.stoveGrpc)
    api(projects.lib.stoveHttp)
    api(projects.lib.stoveKafka)
    api(projects.lib.stoveMongodb)
    api(projects.lib.stoveRdbms)
    api(projects.lib.stovePostgres)
    api(projects.lib.stoveMysql)
    api(projects.lib.stoveMssql)
    api(projects.lib.stoveRedis)
    api(projects.lib.stoveWiremock)
    api(projects.lib.stoveGrpcMock)
    api(projects.lib.stoveTracing)

    // Starters
    api(projects.starters.spring.stoveSpring)
    api(projects.starters.spring.stoveSpringKafka)
    api(projects.starters.ktor.stoveKtor)
    api(projects.starters.micronaut.stoveMicronaut)

    // Extensions
    api(projects.testExtensions.stoveExtensionsKotest)
    api(projects.testExtensions.stoveExtensionsJunit)
  }
}

mavenPublishing {
  coordinates(groupId = rootProject.group.toString(), artifactId = project.name, version = rootProject.version.toString())
  publishToMavenCentral()
  pom {
    name.set(project.name)
    description.set(project.properties["projectDescription"].toString())
    url.set(project.properties["projectUrl"].toString())
    licenses {
      license {
        name.set(project.properties["licence"].toString())
        url.set(project.properties["licenceUrl"].toString())
      }
    }
    developers {
      developer {
        id.set("osoykan")
        name.set("Oguzhan Soykan")
        email.set("oguzhan.soykan@trendyol.com")
      }
    }
    scm {
      connection.set("scm:git@github.com:Trendyol/stove.git")
      developerConnection.set("scm:git:ssh://github.com:Trendyol/stove.git")
      url.set(project.properties["projectUrl"].toString())
    }
  }
  signAllPublications()
}
