package com.nammamedmate.automation.domain;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Decides whether an action must pause in the approvals queue (STORY-006).
 *
 * <p>₹1,00,000 = 100,000 rupees = 10,000,000 paise. ₹50,000 = 5,000,000 paise.
 */
public final class ApprovalRouter {

  public static final long ONE_LAKH_PAISE = 10_000_000L;
  public static final long URGENT_AMOUNT_PAISE = 5_000_000L;
  public static final int MASS_SUSPEND_THRESHOLD = 5;

  private static final Set<String> ALWAYS_REQUIRE =
      Set.of("mass_suspension", "payout_above_1_lakh");
  private static final Set<String> PAYOUT_ACTIONS =
      Set.of(
          "release_payout",
          "mass_payout",
          "process_refund",
          "apply_wallet_credit",
          "payout_above_1_lakh");

  private ApprovalRouter() {}

  public static boolean requiresApproval(
      String actionId,
      Map<String, Object> params,
      Map<String, Object> context,
      Guardrails guardrails,
      ActionDefinition def) {
    Guardrails g = guardrails == null ? Guardrails.NONE : guardrails;
    if (g.requireApproval()) {
      return true;
    }
    if (def != null && def.alwaysRequireApproval()) {
      return true;
    }
    if (actionId != null && ALWAYS_REQUIRE.contains(actionId)) {
      return true;
    }
    if (isMassSuspension(actionId, params, context)) {
      return true;
    }
    Long amount = extractAmount(params, context);
    if (amount != null && amount > ONE_LAKH_PAISE && isPayoutAction(actionId)) {
      return true;
    }
    if (amount != null && g.valueCap() != null && amount > g.valueCap()) {
      return true;
    }
    if (amount != null && g.requireApprovalAbove() != null && amount > g.requireApprovalAbove()) {
      return true;
    }
    return false;
  }

  public static ApprovalUrgency urgency(Long amountPaise, Map<String, Object> context) {
    if (isSlaBreach(context)) {
      return ApprovalUrgency.URGENT;
    }
    if (amountPaise != null && amountPaise > URGENT_AMOUNT_PAISE) {
      return ApprovalUrgency.URGENT;
    }
    return ApprovalUrgency.NORMAL;
  }

  public static ApprovalCategory category(String actionId, ActionDefinition def) {
    String reg = def == null ? null : def.category();
    return ApprovalCategory.fromAction(actionId, reg);
  }

  public static Long extractAmount(Map<String, Object> params, Map<String, Object> context) {
    Long fromParams = amountFrom(params);
    if (fromParams != null) {
      return fromParams;
    }
    Long fromCtx = amountFrom(context);
    if (fromCtx != null) {
      return fromCtx;
    }
    if (context != null) {
      Object payload = context.get("payload");
      if (payload instanceof Map<?, ?> m) {
        return amountFrom(cast(m));
      }
    }
    return null;
  }

  public static boolean isMassSuspension(
      String actionId, Map<String, Object> params, Map<String, Object> context) {
    if ("mass_suspension".equals(actionId)) {
      return true;
    }
    if (!"suspend_entity".equals(actionId)) {
      return false;
    }
    return entityCount(params, context) > MASS_SUSPEND_THRESHOLD;
  }

  public static boolean isPayoutAction(String actionId) {
    return actionId != null && PAYOUT_ACTIONS.contains(actionId);
  }

  public static boolean isSlaBreach(Map<String, Object> context) {
    if (context == null) {
      return false;
    }
    if (truthy(context.get("sla_breach")) || truthy(context.get("sla_breached"))) {
      return true;
    }
    Object trigger = context.get("trigger_id");
    if (trigger == null) {
      trigger = context.get("trigger_event");
    }
    if (trigger != null && String.valueOf(trigger).toLowerCase().contains("sla")) {
      return true;
    }
    Object payload = context.get("payload");
    if (payload instanceof Map<?, ?> m) {
      Map<String, Object> p = cast(m);
      return truthy(p.get("sla_breach")) || truthy(p.get("sla_breached"));
    }
    return false;
  }

  public static String why(
      String actionId,
      Long amountPaise,
      Map<String, Object> params,
      Map<String, Object> context,
      Guardrails guardrails,
      ActionDefinition def) {
    Guardrails g = guardrails == null ? Guardrails.NONE : guardrails;
    if (isMassSuspension(actionId, params, context)) {
      return "Mass suspension of more than "
          + MASS_SUSPEND_THRESHOLD
          + " entities always requires approval.";
    }
    if (amountPaise != null && amountPaise > ONE_LAKH_PAISE && isPayoutAction(actionId)) {
      return "Individual payout above Rs 1,00,000 always requires approval.";
    }
    if (actionId != null && ALWAYS_REQUIRE.contains(actionId)) {
      return "Action type " + actionId + " is on the ALWAYS_REQUIRE_APPROVAL list.";
    }
    if (def != null && def.alwaysRequireApproval()) {
      return "Action type " + actionId + " always requires approval.";
    }
    if (g.requireApproval()) {
      return "Rule is configured with require_approval.";
    }
    if (amountPaise != null && g.valueCap() != null && amountPaise > g.valueCap()) {
      return "Amount Rs "
          + rupees(amountPaise)
          + " exceeds value cap (Rs "
          + rupees(g.valueCap())
          + ").";
    }
    if (amountPaise != null
        && g.requireApprovalAbove() != null
        && amountPaise > g.requireApprovalAbove()) {
      return "Amount Rs "
          + rupees(amountPaise)
          + " exceeds require_approval_above (Rs "
          + rupees(g.requireApprovalAbove())
          + ").";
    }
    return "Action requires human approval.";
  }

  public static String estimatedImpact(
      String actionId, String entityType, String entityName, Long amountPaise) {
    String name = entityName == null || entityName.isBlank() ? "entity" : entityName;
    String type = entityType == null ? "" : entityType;
    if (isPayoutAction(actionId) && amountPaise != null) {
      return "Release Rs " + rupees(amountPaise) + " to " + name + ".";
    }
    if ("suspend_entity".equals(actionId) || "mass_suspension".equals(actionId)) {
      return "Suspend " + type + " " + name + ".";
    }
    return "Execute " + (actionId == null ? "action" : actionId) + " on " + name + ".";
  }

  public static long rupees(long paise) {
    return paise / 100L;
  }

  public static UUID parseUuid(Object o) {
    if (o == null) {
      return null;
    }
    if (o instanceof UUID u) {
      return u;
    }
    try {
      return UUID.fromString(String.valueOf(o));
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  public static String stringVal(Object o) {
    if (o == null) {
      return null;
    }
    String s = String.valueOf(o);
    return s.isBlank() || "null".equalsIgnoreCase(s) ? null : s;
  }

  public static int entityCount(Map<String, Object> params, Map<String, Object> context) {
    Integer n = countFrom(params);
    if (n != null) {
      return n;
    }
    n = countFrom(context);
    return n == null ? 1 : n;
  }

  private static Integer countFrom(Map<String, Object> map) {
    if (map == null) {
      return null;
    }
    Object ids = map.get("entity_ids");
    if (ids instanceof Collection<?> c) {
      return c.size();
    }
    if (ids instanceof Object[] arr) {
      return arr.length;
    }
    Object count = map.get("entity_count");
    if (count == null) {
      count = map.get("count");
    }
    if (count instanceof Number n) {
      return n.intValue();
    }
    return null;
  }

  private static Long amountFrom(Map<String, Object> map) {
    if (map == null) {
      return null;
    }
    Long v = toLong(map.get("amount_paise"));
    if (v != null) {
      return v;
    }
    v = toLong(map.get("payout_amount_paise"));
    if (v != null) {
      return v;
    }
    return toLong(map.get("amount"));
  }

  private static Long toLong(Object o) {
    if (o == null) {
      return null;
    }
    if (o instanceof Number n) {
      return n.longValue();
    }
    String s = String.valueOf(o).trim();
    if (s.isEmpty() || "null".equalsIgnoreCase(s)) {
      return null;
    }
    try {
      return Long.parseLong(s);
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private static boolean truthy(Object o) {
    if (o == null) {
      return false;
    }
    if (o instanceof Boolean b) {
      return b;
    }
    String s = String.valueOf(o).trim();
    return "true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> cast(Map<?, ?> m) {
    return (Map<String, Object>) m;
  }
}
