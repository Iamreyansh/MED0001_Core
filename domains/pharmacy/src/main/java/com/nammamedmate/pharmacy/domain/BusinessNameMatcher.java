package com.nammamedmate.pharmacy.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Token-level Levenshtein distance for GSTIN business name cross-check. */
public final class BusinessNameMatcher {

  private BusinessNameMatcher() {}

  public static int tokenDistance(String a, String b) {
    List<String> tokensA = tokenize(a);
    List<String> tokensB = tokenize(b);
    return levenshtein(tokensA, tokensB);
  }

  public static boolean isSignificantMismatch(String platformName, String apiName) {
    return tokenDistance(platformName, apiName) > 5;
  }

  static List<String> tokenize(String raw) {
    if (raw == null || raw.isBlank()) {
      return List.of();
    }
    String normalized = raw.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9\\s]", " ");
    String[] parts = normalized.split("\\s");
    List<String> tokens = new ArrayList<>();
    for (String part : parts) {
      if (!part.isBlank()) {
        tokens.add(part);
      }
    }
    return tokens;
  }

  static int levenshtein(List<String> a, List<String> b) {
    int n = a.size();
    int m = b.size();
    int[][] dp = new int[n + 1][m + 1];
    for (int i = 0; i <= n; i++) {
      dp[i][0] = i;
    }
    for (int j = 0; j <= m; j++) {
      dp[0][j] = j;
    }
    for (int i = 1; i <= n; i++) {
      for (int j = 1; j <= m; j++) {
        int cost = a.get(i - 1).equals(b.get(j - 1)) ? 0 : 1;
        dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
      }
    }
    return dp[n][m];
  }
}
