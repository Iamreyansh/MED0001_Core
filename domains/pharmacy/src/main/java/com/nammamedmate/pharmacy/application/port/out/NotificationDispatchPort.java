package com.nammamedmate.pharmacy.application.port.out;

import java.util.List;
import java.util.UUID;

/** Async notification dispatch for pharmacy alerts (EPIC-002 via outbox). */
public interface NotificationDispatchPort {

  void dispatchPerformanceAlert(
      UUID pharmacyId, String alertType, String message, List<String> channels);

  void dispatchSettlementReleased(UUID pharmacyId, UUID settlementId, long netPaidPaise);

  void dispatchSettlementPaid(
      UUID pharmacyId, UUID settlementId, long netPaidPaise, String utrNumber);

  void dispatchSettlementHeld(UUID pharmacyId, UUID settlementId, String reason);

  void dispatchPharmacyNotice(
      UUID pharmacyId,
      List<String> channels,
      String templateName,
      String subject,
      String message,
      String priority);
}
