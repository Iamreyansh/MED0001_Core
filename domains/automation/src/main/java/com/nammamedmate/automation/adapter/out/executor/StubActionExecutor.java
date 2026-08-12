package com.nammamedmate.automation.adapter.out.executor;

import com.nammamedmate.automation.application.port.out.ActionExecutorPort;
import com.nammamedmate.automation.application.port.out.ActivityLogPort;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Stub action dispatch: logs EXECUTED + before/after snapshots (outbox in later stories). */
public class StubActionExecutor implements ActionExecutorPort {

  private static final Logger log = LoggerFactory.getLogger(StubActionExecutor.class);

  private final ActivityLogPort activityLog;

  public StubActionExecutor(ActivityLogPort activityLog) {
    this.activityLog = activityLog;
  }

  @Override
  public UUID execute(String actionId, Map<String, Object> params, Map<String, Object> context) {
    log.debug("Stub execute action_id={} params={}", actionId, params);
    Map<String, Object> ctx = context == null ? Map.of() : context;
    Map<String, Object> detail = new LinkedHashMap<>(ctx);
    detail.put("params", params == null ? Map.of() : params);
    detail.putIfAbsent("before_state", before(actionId, ctx));
    detail.putIfAbsent("after_state", after(actionId, ctx));
    detail.putIfAbsent("actor", "AUTOMATION");
    return activityLog.append(actionId, "EXECUTED", "executed", detail);
  }

  private static Map<String, Object> before(String actionId, Map<String, Object> ctx) {
    if ("suspend_entity".equals(actionId)) {
      return Map.of("status", "ACTIVE");
    }
    if ("apply_wallet_credit".equals(actionId)) {
      return Map.of("wallet_credited", false);
    }
    if ("auto_assign_rider".equals(actionId)) {
      return Map.of("order_status", "PLACED", "rider_id", "");
    }
    Map<String, Object> s = new LinkedHashMap<>();
    if (ctx.get("entity_type") != null) {
      s.put("entity_type", ctx.get("entity_type"));
    }
    if (ctx.get("entity_id") != null) {
      s.put("entity_id", ctx.get("entity_id"));
    }
    return s;
  }

  private static Map<String, Object> after(String actionId, Map<String, Object> ctx) {
    if ("suspend_entity".equals(actionId)) {
      return Map.of("status", "SUSPENDED");
    }
    if ("apply_wallet_credit".equals(actionId)) {
      return Map.of("wallet_credited", true);
    }
    if ("auto_assign_rider".equals(actionId)) {
      return Map.of("order_status", "ACCEPTED", "rider_id", "assigned");
    }
    if ("reactivate_entity".equals(actionId)) {
      return Map.of("status", "ACTIVE");
    }
    Map<String, Object> s = new LinkedHashMap<>();
    s.put("applied", true);
    s.put("action", actionId == null ? "" : actionId);
    if (ctx.get("entity_type") != null) {
      s.put("entity_type", ctx.get("entity_type"));
    }
    return s;
  }
}
