package com.nammamedmate.testing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ContainersTest {

  @Test
  void buildsJdbcUrl() {
    assertThat(Containers.postgresJdbcUrl("localhost", 5432, "medmate"))
        .isEqualTo("jdbc:postgresql://localhost:5432/medmate");
    assertThat(Containers.POSTGRES_IMAGE).contains("postgres");
    assertThat(Containers.LOCALSTACK_IMAGE).contains("localstack");
  }
}
