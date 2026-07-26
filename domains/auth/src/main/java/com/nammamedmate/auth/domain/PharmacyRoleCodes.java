package com.nammamedmate.auth.domain;

/**
 * Pharmacy system role helpers. JWT still collapses owner → pharmacy_owner, else pharmacy_staff.
 */
public final class PharmacyRoleCodes {

  public static final String OWNER = "owner";
  public static final String MANAGER = "manager";
  public static final String PHARMACIST = "pharmacist";
  public static final String CASHIER = "cashier";
  public static final String DELIVERY = "delivery";

  private PharmacyRoleCodes() {}

  public static boolean isOwner(String code) {
    return OWNER.equals(code);
  }

  public static String systemApiId(String code) {
    return "system-" + code;
  }

  public static boolean isSystemApiId(String id) {
    return id != null && id.startsWith("system-");
  }

  public static String codeFromSystemApiId(String id) {
    if (!isSystemApiId(id)) {
      return null;
    }
    return id.substring("system-".length());
  }
}
