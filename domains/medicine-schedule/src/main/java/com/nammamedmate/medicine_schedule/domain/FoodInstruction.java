package com.nammamedmate.medicine_schedule.domain;

public enum FoodInstruction {
  BEFORE,
  AFTER,
  ANY;

  public static FoodInstruction parse(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("food_instruction is required");
    }
    try {
      return FoodInstruction.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Invalid food_instruction: " + raw);
    }
  }
}
