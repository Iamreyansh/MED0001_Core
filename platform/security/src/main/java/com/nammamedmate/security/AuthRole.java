package com.nammamedmate.security;

public enum AuthRole {
  CUSTOMER("customer"),
  PHARMACY_OWNER("pharmacy_owner"),
  PHARMACY_STAFF("pharmacy_staff"),
  RIDER("rider"),
  ADMIN_SUPER("admin_super"),
  ADMIN_OPERATIONS("admin_operations"),
  ADMIN_FINANCE("admin_finance"),
  ADMIN_SUPPORT("admin_support"),
  ADMIN_COMPLIANCE("admin_compliance");

  private final String value;

  AuthRole(String value) {
    this.value = value;
  }

  public String value() {
    return value;
  }

  public static AuthRole fromValue(String value) {
    for (AuthRole role : values()) {
      if (role.value.equals(value)) {
        return role;
      }
    }
    throw new IllegalArgumentException("Unknown role: " + value);
  }
}
