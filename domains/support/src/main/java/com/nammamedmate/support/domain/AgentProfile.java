package com.nammamedmate.support.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AgentProfile(
    UUID adminUserId,
    List<String> specialties,
    boolean online,
    int maxLoad,
    String displayName,
    Instant updatedAt) {

  public AgentProfile {
    specialties =
        specialties == null
            ? List.of()
            : List.copyOf(specialties.stream().filter(s -> s != null).toList());
    if (maxLoad < 1) {
      maxLoad = 20;
    }
  }

  public boolean matchesSpecialty(TicketCategory category) {
    if (specialties.isEmpty()) {
      return true;
    }
    String cat = category.name();
    for (String s : specialties) {
      if (s.equalsIgnoreCase(cat)) {
        return true;
      }
    }
    return false;
  }
}
