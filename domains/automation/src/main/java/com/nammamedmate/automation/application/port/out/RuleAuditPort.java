package com.nammamedmate.automation.application.port.out;

import java.util.Map;
import java.util.UUID;

/** Rule CRUD audit; activity execution log is STORY-005 automation_activity_log. */
public interface RuleAuditPort {

  void log(String action, UUID ruleId, UUID actorId, Map<String, Object> diff);
}
