package com.nammamedmate.kernel.storage;

/**
 * Object-key prefixes under the env uploads bucket ({@code med0001-{env}-uploads-105927215604}).
 * Never put objects at the bucket root — always use a typed prefix.
 */
public final class StorageObjectKeys {

  public static final String AVATARS = "avatars";
  public static final String EXPORTS = "exports";
  public static final String PRESCRIPTIONS = "prescriptions";
  public static final String EPRESCRIPTIONS = "eprescriptions";
  public static final String KYC = "kyc";
  public static final String PHARMACIES = "pharmacies";
  public static final String PRODUCTS = "products";
  public static final String DOCTORS = "doctors";
  public static final String RIDERS = "riders";
  public static final String BANNERS = "banners";
  public static final String EVIDENCE = "evidence";
  public static final String ATTACHMENTS = "attachments";
  public static final String INVOICES = "invoices";
  public static final String REPORTS = "reports";

  private StorageObjectKeys() {}

  public static String avatar(String objectName) {
    return key(AVATARS, objectName);
  }

  public static String export(String objectName) {
    return key(EXPORTS, objectName);
  }

  public static String key(String prefix, String objectName) {
    if (prefix == null || prefix.isBlank()) {
      throw new IllegalArgumentException("prefix required");
    }
    if (objectName == null || objectName.isBlank()) {
      throw new IllegalArgumentException("objectName required");
    }
    String name = objectName.startsWith("/") ? objectName.substring(1) : objectName;
    if (name.isBlank() || name.contains("..")) {
      throw new IllegalArgumentException("invalid objectName");
    }
    return prefix + "/" + name;
  }
}
