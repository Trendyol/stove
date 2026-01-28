package stove.spring.standalone.example.infrastructure.postgres

import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.v1.jdbc.Database
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.*
import javax.sql.DataSource

@ConfigurationProperties("spring.datasource")
data class DataSourceConfig(
  val url: String,
  val username: String,
  val password: String
)

@Configuration
class ExposedConfiguration {
  @Bean
  fun dataSource(
    dataSourceConfig: DataSourceConfig
  ): DataSource = HikariDataSource().apply {
    this.jdbcUrl = dataSourceConfig.url
    this.username = dataSourceConfig.username
    this.password = dataSourceConfig.password
  }

  @Bean
  fun database(dataSource: DataSource): Database = Database.connect(dataSource)
}
