package com.nammamedmate.testing;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Shared Testcontainers entrypoints for story/integration tests. */
public final class Containers {

  public static final String POSTGRES_IMAGE = "postgres:16-alpine";
  public static final String REDIS_IMAGE = "redis:7-alpine";
  public static final String LOCALSTACK_IMAGE = "localstack/localstack:4.0";

  private Containers() {}

  public static String postgresJdbcUrl(String host, int port, String db) {
    return "jdbc:postgresql://" + host + ":" + port + "/" + db;
  }

  @SuppressWarnings("resource")
  public static PostgreSQLContainer<?> newPostgres() {
    return new PostgreSQLContainer<>(DockerImageName.parse(POSTGRES_IMAGE))
        .withDatabaseName("medmate")
        .withUsername("medmate")
        .withPassword("medmate");
  }

  @SuppressWarnings("resource")
  public static GenericContainer<?> newRedis() {
    return new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE)).withExposedPorts(6379);
  }
}
