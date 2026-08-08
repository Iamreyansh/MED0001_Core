package com.nammamedmate.settings.domain;

import java.util.Set;

public final class FeatureFlagEnvironments {

  public static final String PRODUCTION = "production";
  public static final String STAGING = "staging";
  public static final String DEVELOPMENT = "development";
  public static final Set<String> ALL = Set.of(PRODUCTION, STAGING, DEVELOPMENT);

  private FeatureFlagEnvironments() {}

  public static boolean isValid(String environment) {
    return environment != null && ALL.contains(environment);
  }
}
