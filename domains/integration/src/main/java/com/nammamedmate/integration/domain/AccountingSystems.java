package com.nammamedmate.integration.domain;

public final class AccountingSystems {

  public static final String TALLY = "TALLY";
  public static final String ZOHO_BOOKS = "ZOHO_BOOKS";

  private AccountingSystems() {}

  public static boolean isValid(String value) {
    return TALLY.equals(value) || ZOHO_BOOKS.equals(value);
  }
}
