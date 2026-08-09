package com.nammamedmate.integration.domain;

public final class AccountingSyncStatuses {

  public static final String QUEUED = "QUEUED";
  public static final String RUNNING = "RUNNING";
  public static final String COMPLETED = "COMPLETED";
  public static final String FAILED = "FAILED";

  private AccountingSyncStatuses() {}

  public static boolean isActive(String status) {
    return QUEUED.equals(status) || RUNNING.equals(status);
  }
}
