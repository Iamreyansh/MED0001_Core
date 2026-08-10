package com.nammamedmate.support.application.port.out;

import java.util.Map;
import java.util.UUID;

/** Local audit append; stub publishes outbox / no-ops until settings bridge. */
public interface SupportAuditPort {

  void append(
      String entityType,
      UUID actorId,
      String actorRole,
      UUID entityId,
      String action,
      Map<String, Object> before,
      Map<String, Object> after);

  default void appendSlaPolicy(
      UUID actorId,
      String actorRole,
      UUID policyId,
      Map<String, Object> before,
      Map<String, Object> after) {
    append("support_sla_policy", actorId, actorRole, policyId, "SLA_POLICY_UPDATED", before, after);
  }

  default void appendEscalationMatrix(
      UUID actorId, String actorRole, Map<String, Object> before, Map<String, Object> after) {
    append(
        "support_escalation_matrix",
        actorId,
        actorRole,
        null,
        "ESCALATION_MATRIX_UPDATED",
        before,
        after);
  }
}
