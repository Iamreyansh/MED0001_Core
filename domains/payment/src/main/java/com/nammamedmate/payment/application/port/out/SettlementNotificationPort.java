package com.nammamedmate.payment.application.port.out;

import java.util.UUID;

/** Outbox-backed settlement notifications (ids only — EPIC-017 consumes). */
public interface SettlementNotificationPort {

  void settlementReleased(UUID pharmacyId, UUID settlementId, long netPaise);

  void settlementHeld(UUID pharmacyId, UUID settlementId, String reason);
}
