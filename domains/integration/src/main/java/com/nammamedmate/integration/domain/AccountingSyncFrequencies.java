package com.nammamedmate.integration.domain;

public final class AccountingSyncFrequencies {

  public static final String DAILY = "DAILY";
  public static final String WEEKLY = "WEEKLY";

  private AccountingSyncFrequencies() {}

  public static boolean isValid(String value) {
    return DAILY.equals(value) || WEEKLY.equals(value);
  }
}
