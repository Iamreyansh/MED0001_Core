package com.nammamedmate.integration.domain;

public final class CommunicationStatuses {

  public static final String HEALTHY = "HEALTHY";
  public static final String DEGRADED = "DEGRADED";
  public static final String DOWN = "DOWN";

  private CommunicationStatuses() {}

  public static boolean isHealthy(String status) {
    return HEALTHY.equals(status);
  }
}
