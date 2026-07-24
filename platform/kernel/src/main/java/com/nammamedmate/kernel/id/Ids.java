package com.nammamedmate.kernel.id;

import java.util.UUID;

public final class Ids {

  private Ids() {}

  public static UUID newId() {
    return UUID.randomUUID();
  }

  public static UUID parse(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("id must not be blank");
    }
    return UUID.fromString(value.trim());
  }
}
