package com.nammamedmate.api.support;

import com.nammamedmate.testing.Containers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Process-wide Postgres + Redis for API integration tests. Started once; Ryuk cleans up on JVM
 * exit.
 */
public final class SharedContainers {

  private static final Object LOCK = new Object();
  private static volatile boolean started;

  @SuppressWarnings("resource")
  private static final PostgreSQLContainer<?> POSTGRES = Containers.newPostgres();

  @SuppressWarnings("resource")
  private static final GenericContainer<?> REDIS = Containers.newRedis();

  private SharedContainers() {}

  public static void start() {
    if (started) {
      return;
    }
    synchronized (LOCK) {
      if (started) {
        return;
      }
      POSTGRES.start();
      REDIS.start();
      started = true;
    }
  }

  public static String jdbcUrl() {
    start();
    return POSTGRES.getJdbcUrl();
  }

  public static String username() {
    start();
    return POSTGRES.getUsername();
  }

  public static String password() {
    start();
    return POSTGRES.getPassword();
  }

  public static String redisHost() {
    start();
    return REDIS.getHost();
  }

  public static int redisPort() {
    start();
    return REDIS.getMappedPort(6379);
  }
}
