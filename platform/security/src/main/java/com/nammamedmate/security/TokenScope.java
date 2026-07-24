package com.nammamedmate.security;

public enum TokenScope {
  FULL("full"),
  POS("pos"),
  MFA_CHALLENGE("mfa_challenge");

  private final String value;

  TokenScope(String value) {
    this.value = value;
  }

  public String value() {
    return value;
  }

  public static TokenScope fromValue(String value) {
    for (TokenScope scope : values()) {
      if (scope.value.equals(value)) {
        return scope;
      }
    }
    throw new IllegalArgumentException("Unknown token_scope: " + value);
  }
}
