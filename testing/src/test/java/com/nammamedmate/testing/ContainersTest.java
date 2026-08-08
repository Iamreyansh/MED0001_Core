package com.nammamedmate.testing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ContainersTest {

  @Test
  void buildsJdbcUrlAndImageConstants() {
    assertThat(Containers.postgresJdbcUrl("localhost", 5432, "medmate"))
        .isEqualTo("jdbc:postgresql://localhost:5432/medmate");
    assertThat(Containers.POSTGRES_IMAGE).contains("postgis");
    assertThat(Containers.REDIS_IMAGE).contains("redis");
    assertThat(Containers.LOCALSTACK_IMAGE).contains("localstack");
  }

  @Test
  void factoryMethodsConfigureContainers() {
    try (var postgres = Containers.newPostgres();
        var redis = Containers.newRedis()) {
      assertThat(postgres.getDatabaseName()).isEqualTo("medmate");
      assertThat(postgres.getUsername()).isEqualTo("medmate");
      assertThat(redis.getExposedPorts()).contains(6379);
    }
  }
}
