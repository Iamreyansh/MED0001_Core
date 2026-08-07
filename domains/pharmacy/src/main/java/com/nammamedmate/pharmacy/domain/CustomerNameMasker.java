package com.nammamedmate.pharmacy.domain;

/** Masks customer names for admin views: first name + last initial (e.g. Priya K.). */
public final class CustomerNameMasker {

  private CustomerNameMasker() {}

  public static String mask(String fullName) {
    if (fullName == null || fullName.isBlank()) {
      return "";
    }
    String[] parts = fullName.trim().split("\\s+");
    if (parts.length == 1) {
      return parts[0];
    }
    String last = parts[parts.length - 1];
    return parts[0] + " " + Character.toUpperCase(last.charAt(0)) + ".";
  }
}
