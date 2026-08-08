package com.nammamedmate.rider.application.port.out;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface RiderBadgeStore {

  record BadgeRow(String badge, LocalDate earnedAt) {}

  List<BadgeRow> listForRider(UUID riderId);

  default void upsert(UUID id, UUID riderId, String badge, LocalDate earnedAt) {}
}
