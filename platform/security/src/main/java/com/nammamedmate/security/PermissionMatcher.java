package com.nammamedmate.security;

/**
 * Matches granted {@code resource:action} permissions against a required permission, expanding
 * wildcards ({@code *:*}, {@code orders:*}, bare {@code *}).
 */
public final class PermissionMatcher {

  private PermissionMatcher() {}

  public static boolean allows(Iterable<String> granted, String required) {
    if (required == null || required.isBlank()) {
      return false;
    }
    String need = required.trim();
    for (String raw : granted) {
      if (raw == null || raw.isBlank()) {
        continue;
      }
      if (matches(raw.trim(), need)) {
        return true;
      }
    }
    return false;
  }

  static boolean matches(String granted, String required) {
    if ("*".equals(granted) || "*:*".equals(granted)) {
      return true;
    }
    if (granted.equals(required)) {
      return true;
    }
    int gSep = granted.indexOf(':');
    int rSep = required.indexOf(':');
    if (gSep < 0 || rSep < 0) {
      return false;
    }
    String gRes = granted.substring(0, gSep);
    String gAct = granted.substring(gSep + 1);
    String rRes = required.substring(0, rSep);
    String rAct = required.substring(rSep + 1);
    if (!gRes.equals(rRes) && !"*".equals(gRes)) {
      return false;
    }
    return "*".equals(gAct) || gAct.equals(rAct);
  }
}
