package com.nammamedmate.integration.domain;

public final class AccountingSyncTypes {

  public static final String SALES = "SALES";
  public static final String PURCHASES = "PURCHASES";
  public static final String EXPENSES = "EXPENSES";
  public static final String GST = "GST";

  private AccountingSyncTypes() {}

  public static boolean isValid(String value) {
    return SALES.equals(value)
        || PURCHASES.equals(value)
        || EXPENSES.equals(value)
        || GST.equals(value);
  }

  public static boolean isTallyExportable(String value) {
    return SALES.equals(value) || PURCHASES.equals(value) || GST.equals(value);
  }
}
