package com.nammamedmate.auth.domain;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Eight single-use backup codes in XXXX-XXXX form. */
public final class BackupCodes {

  private static final char[] ALPHANUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();
  public static final int COUNT = 8;

  private BackupCodes() {}

  public static List<String> generate(SecureRandom random) {
    Set<String> codes = new LinkedHashSet<>();
    while (codes.size() < COUNT) {
      codes.add(one(random));
    }
    return List.copyOf(codes);
  }

  static String one(SecureRandom random) {
    char[] left = new char[4];
    char[] right = new char[4];
    for (int i = 0; i < 4; i++) {
      left[i] = ALPHANUM[random.nextInt(ALPHANUM.length)];
      right[i] = ALPHANUM[random.nextInt(ALPHANUM.length)];
    }
    return new String(left) + "-" + new String(right);
  }

  public static String normalise(String code) {
    if (code == null) {
      return null;
    }
    return code.trim().toUpperCase(Locale.ROOT);
  }

  public static boolean looksLikeBackupCode(String code) {
    String n = normalise(code);
    return n != null && n.matches("[A-Z0-9]{4}-[A-Z0-9]{4}");
  }

  /** JSONB rows: { "hash": "...", "used_at": null|ISO-8601 }. */
  public static List<Map<String, Object>> toStoredRows(List<String> plaintext, Hasher hasher) {
    List<Map<String, Object>> rows = new ArrayList<>(plaintext.size());
    for (String code : plaintext) {
      Map<String, Object> row = new java.util.HashMap<>();
      row.put("hash", hasher.hash(normalise(code)));
      row.put("used_at", null);
      rows.add(row);
    }
    return rows;
  }

  @FunctionalInterface
  public interface Hasher {
    String hash(String value);
  }
}
