package com.nammamedmate.prescription.application.port.out;

import java.util.UUID;

/** Outbox-only notifications (ids + reason codes; no PII phones). */
public interface NotificationDispatchPort {

  void notifyCustomerRxRejected(UUID customerId, UUID rxId, String reason, String customMessage);

  void notifyPharmacyOwnerOverdue(UUID pharmacyId, UUID rxId);

  /** Compliance team alert when audit past deadline → OVERDUE_AUDIT. */
  default void notifyComplianceOverdueAudit(UUID rxId, UUID pharmacyId) {}

  /** Head of Compliance email for MEDIUM/HIGH severity flags. */
  default void notifyHeadOfComplianceFlag(UUID rxId, String severity, String reason) {}

  /** Soft alert when a doctor exceeds scheduled H1/X volume in a 30-day window. */
  default void notifyComplianceDoctorScheduleAlert(UUID doctorId, long count30d) {}

  /** Compliance alert when a doctor is blacklisted. */
  default void notifyComplianceDoctorBlacklisted(UUID doctorId, String reason) {}

  /** Email admin_compliance + admin_super when a filing becomes OVERDUE (or 3-day escalation). */
  default void notifyComplianceFilingOverdue(
      UUID filingId, String filingType, boolean escalation) {}

  /** WhatsApp alert to pharmacy owners when a drug batch is recalled. */
  default void notifyPharmacyDrugRecall(UUID pharmacyId, String drugName, String batchNo) {}
}
