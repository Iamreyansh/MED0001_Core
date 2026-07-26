package com.nammamedmate.pharmacy.domain;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Valid Indian state/UT display names (case-insensitive). */
public final class IndianStates {

  private static final Map<String, String> BY_NORMALISED =
      Map.ofEntries(
          e("ANDAMAN AND NICOBAR ISLANDS", "Andaman and Nicobar Islands"),
          e("ANDHRA PRADESH", "Andhra Pradesh"),
          e("ARUNACHAL PRADESH", "Arunachal Pradesh"),
          e("ASSAM", "Assam"),
          e("BIHAR", "Bihar"),
          e("CHANDIGARH", "Chandigarh"),
          e("CHHATTISGARH", "Chhattisgarh"),
          e("DADRA AND NAGAR HAVELI AND DAMAN AND DIU", "Dadra and Nagar Haveli and Daman and Diu"),
          e("DELHI", "Delhi"),
          e("GOA", "Goa"),
          e("GUJARAT", "Gujarat"),
          e("HARYANA", "Haryana"),
          e("HIMACHAL PRADESH", "Himachal Pradesh"),
          e("JAMMU AND KASHMIR", "Jammu and Kashmir"),
          e("JHARKHAND", "Jharkhand"),
          e("KARNATAKA", "Karnataka"),
          e("KERALA", "Kerala"),
          e("LADAKH", "Ladakh"),
          e("LAKSHADWEEP", "Lakshadweep"),
          e("MADHYA PRADESH", "Madhya Pradesh"),
          e("MAHARASHTRA", "Maharashtra"),
          e("MANIPUR", "Manipur"),
          e("MEGHALAYA", "Meghalaya"),
          e("MIZORAM", "Mizoram"),
          e("NAGALAND", "Nagaland"),
          e("ODISHA", "Odisha"),
          e("PUDUCHERRY", "Puducherry"),
          e("PUNJAB", "Punjab"),
          e("RAJASTHAN", "Rajasthan"),
          e("SIKKIM", "Sikkim"),
          e("TAMIL NADU", "Tamil Nadu"),
          e("TELANGANA", "Telangana"),
          e("TRIPURA", "Tripura"),
          e("UTTAR PRADESH", "Uttar Pradesh"),
          e("UTTARAKHAND", "Uttarakhand"),
          e("WEST BENGAL", "West Bengal"));

  public static final Set<String> NAMES = Set.copyOf(BY_NORMALISED.values());

  private IndianStates() {}

  public static String requireValid(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("MISSING_REQUIRED_FIELD");
    }
    String key = raw.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
    String canonical = BY_NORMALISED.get(key);
    if (canonical == null) {
      throw new IllegalArgumentException("INVALID_STATE");
    }
    return canonical;
  }

  private static Map.Entry<String, String> e(String k, String v) {
    return Map.entry(k, v);
  }
}
