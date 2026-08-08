package com.nammamedmate.settings.application.port.out;

import java.util.Map;
import java.util.UUID;

/** Minimal append to audit_log; must not fail the request. */
public interface AdminAuditAppendPort {

  void append(
      String entityType,
      UUID actorId,
      String actorRole,
      UUID entityId,
      String action,
      Map<String, Object> before,
      Map<String, Object> after);

  /** Convenience for admin_staff entity audits (STORY-001). */
  default void append(
      UUID actorId,
      String actorRole,
      UUID entityId,
      String action,
      Map<String, Object> before,
      Map<String, Object> after) {
    append("admin_staff", actorId, actorRole, entityId, action, before, after);
  }
}
