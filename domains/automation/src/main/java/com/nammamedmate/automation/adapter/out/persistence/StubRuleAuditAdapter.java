package com.nammamedmate.automation.adapter.out.persistence;

import com.nammamedmate.automation.application.port.out.RuleAuditPort;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Rule CRUD audit stays separate from automation_activity_log (STORY-005). */
@Component
public class StubRuleAuditAdapter implements RuleAuditPort {

  private static final Logger log = LoggerFactory.getLogger(StubRuleAuditAdapter.class);

  private final CopyOnWriteArrayList<Map<String, Object>> entries = new CopyOnWriteArrayList<>();

  @Override
  public void log(String action, UUID ruleId, UUID actorId, Map<String, Object> diff) {
    Map<String, Object> row =
        Map.of(
            "action",
            action == null ? "" : action,
            "rule_id",
            ruleId == null ? "" : ruleId.toString(),
            "actor_id",
            actorId == null ? "" : actorId.toString(),
            "diff",
            diff == null ? Map.of() : diff);
    entries.add(row);
    log.info("automation rule audit action={} rule_id={} actor={}", action, ruleId, actorId);
  }

  public java.util.List<Map<String, Object>> entries() {
    return java.util.List.copyOf(entries);
  }
}
