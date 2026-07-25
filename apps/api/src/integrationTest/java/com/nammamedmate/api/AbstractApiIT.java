package com.nammamedmate.api;

import com.nammamedmate.api.support.SharedContainers;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
public abstract class AbstractApiIT {

  @LocalServerPort protected int port;

  @DynamicPropertySource
  static void registerProps(DynamicPropertyRegistry registry) {
    SharedContainers.start();
    registry.add("spring.datasource.url", SharedContainers::jdbcUrl);
    registry.add("spring.datasource.username", SharedContainers::username);
    registry.add("spring.datasource.password", SharedContainers::password);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    registry.add("spring.flyway.enabled", () -> "true");
    registry.add("spring.data.redis.host", SharedContainers::redisHost);
    registry.add("spring.data.redis.port", () -> SharedContainers.redisPort());
  }

  protected String baseUrl() {
    return "http://localhost:" + port;
  }
}
