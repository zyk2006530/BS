package cn.edu.approval.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Wires the database only for the {@code db} profile.
 *
 * <p>{@code DataSourceAutoConfiguration} is excluded at the application level so the default and
 * test profiles do not require a database. Defining the data source here lets Flyway and
 * JdbcTemplate auto-configuration proceed only when the profile is explicitly activated.
 */
@Configuration(proxyBeanMethods = false)
@Profile("db")
class DatabaseProfileConfiguration {
  @Bean
  @ConfigurationProperties("spring.datasource")
  DataSourceProperties dataSourceProperties() {
    return new DataSourceProperties();
  }

  @Bean
  @ConfigurationProperties("spring.datasource.hikari")
  HikariDataSource dataSource(DataSourceProperties properties) {
    return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
  }
}
