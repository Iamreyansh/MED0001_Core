package com.nammamedmate.crm.application.port.out;

import java.util.Map;
import java.util.UUID;

/** Append-only audit for plan price/limit changes (must not fail the request). */
public interface CrmAuditPort {

  void append(
      String entityType,
      UUID actorId,
      String actorRole,
      UUID entityId,
      String action,
      Map<String, Object> before,
      Map<String, Object> after);
}
