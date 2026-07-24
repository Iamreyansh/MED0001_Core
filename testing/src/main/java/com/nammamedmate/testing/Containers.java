package com.nammamedmate.testing;

/** Shared Testcontainers entrypoints for story/integration tests. */
public final class Containers {

  private Containers() {}

  public static final String POSTGRES_IMAGE = "postgres:16-alpine";
  public static final String LOCALSTACK_IMAGE = "localstack/localstack:4.0";

  public static String postgresJdbcUrl(String host, int port, String db) {
    return "jdbc:postgresql://" + host + ":" + port + "/" + db;
  }
}
