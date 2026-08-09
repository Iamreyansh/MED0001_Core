package com.nammamedmate.integration.domain;

public final class EntityTypes {

  public static final String PHARMACY = "PHARMACY";
  public static final String RIDER = "RIDER";

  private EntityTypes() {}

  public static boolean isValid(String entityType) {
    return PHARMACY.equals(entityType) || RIDER.equals(entityType);
  }
}
