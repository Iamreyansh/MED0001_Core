package com.nammamedmate.marketing.application.port.out;

import java.util.Map;
import java.util.UUID;

/** Append-only audit for banner admin mutations (must not fail the request). */
public interface MarketingAuditPort {

  void append(
      String entityType,
      UUID actorId,
      String actorRole,
      UUID entityId,
      String action,
      Map<String, Object> before,
      Map<String, Object> after);
}
