package com.nammamedmate.automation.domain;

import java.util.List;
import java.util.Map;

/** Specs for the six catalog seeds and three seed workflows (all created INACTIVE). */
public final class SeedDefinitions {

  public static final String AUTO_ASSIGN = "AUTO_ASSIGN_UNASSIGNED_ORDERS";
  public static final String AUTO_PAYOUT = "AUTO_RELEASE_DUE_PAYOUTS";
  public static final String AUTO_DUNNING = "AUTO_DUNNING_OVERDUE_INVOICES";
  public static final String AUTO_ESCALATE = "AUTO_ESCALATE_BREACHED_TICKETS";
  public static final String AUTO_HEALTH = "AUTO_SAVE_PLAY_HEALTH_DROP";
  public static final String AUTO_SCHEDULE_X = "AUTO_FLAG_SCHEDULE_X";

  public static final String WF_DUNNING = "DUNNING_LADDER";
  public static final String WF_ONBOARDING = "PHARMACY_ONBOARDING";
  public static final String WF_WIN_BACK = "WIN_BACK";

  public static final long PAYOUT_CAP_PAISE = 5_000_000L;

  public static final String TICKET_SLA_TRIGGER = "support_sla_breaching";

  private SeedDefinitions() {}

  public record RuleSeed(
      String key,
      String name,
      String triggerId,
      Map<String, Object> triggerParams,
      List<ConditionSpec> conditions,
      List<ActionSpec> actions,
      Guardrails guardrails,
      String expectedImpact,
      String edgeCases,
      int displayOrder) {
    public RuleSeed {
      triggerParams = triggerParams == null ? Map.of() : Map.copyOf(triggerParams);
      conditions = conditions == null ? List.of() : List.copyOf(conditions);
      actions = actions == null ? List.of() : List.copyOf(actions);
    }
  }

  public record WorkflowSeed(
      String key, String triggerId, String description, List<WorkflowStep> steps) {
    public WorkflowSeed {
      steps = steps == null ? List.of() : List.copyOf(steps);
    }
  }

  public static List<RuleSeed> ruleSeeds() {
    return List.of(autoAssign(), autoPayout(), autoEscalate(), autoHealth(), autoScheduleX());
  }

  public static List<WorkflowSeed> workflowSeeds() {
    return List.of(dunningLadder(), pharmacyOnboarding(), winBack());
  }

  public static String dunningImpact() {
    return "Systematic dunning reduces overdue invoice tail by ~40%; avoids manual CSM follow-up";
  }

  public static String dunningEdges() {
    return "Pharmacy pays between steps - invoice.status changes to PAID, BRANCH step detects"
        + " payment, cancels execution";
  }

  public static RuleSeed autoAssign() {
    return new RuleSeed(
        AUTO_ASSIGN,
        "Auto-Assign Unassigned Orders",
        "order_unassigned",
        Map.of("duration_minutes", 5),
        List.of(new ConditionSpec("zone.coverage_status", "not_eq", "NO_RIDERS")),
        List.of(new ActionSpec("auto_assign_rider", Map.of(), false)),
        new Guardrails(new Guardrails.RateLimit(60, 60), null, null),
        "Automatically dispatches ~80% of unassigned orders within 5 minutes; reduces manual"
            + " dispatch load",
        "If zone has no available riders (NO_RIDERS status), rule does not fire - human"
            + " intervention needed",
        1);
  }

  public static RuleSeed autoPayout() {
    return new RuleSeed(
        AUTO_PAYOUT,
        "Auto-Release Due Payouts",
        "payout_cycle_reached",
        Map.of(),
        List.of(new ConditionSpec("payout.amount_paise", "lt", PAYOUT_CAP_PAISE)),
        List.of(new ActionSpec("release_payout", Map.of(), false)),
        new Guardrails(null, PAYOUT_CAP_PAISE, PAYOUT_CAP_PAISE),
        "Automates ~90% of pharmacy and rider payouts; only high-value payouts need approval",
        "Failed KYC or inactive fund accounts cause payout to fail; activity log error for human"
            + " review",
        2);
  }

  public static RuleSeed autoEscalate() {
    return new RuleSeed(
        AUTO_ESCALATE,
        "Auto-Escalate Breached Tickets",
        TICKET_SLA_TRIGGER,
        Map.of("minutes_to_breach", 5),
        List.of(new ConditionSpec("ticket.sla_level", "in", List.of("L1", "L2", "L3"))),
        List.of(
            new ActionSpec("escalate_ticket", Map.of(), false),
            new ActionSpec(
                "send_notification",
                Map.of(
                    "channel", "PUSH",
                    "template_id", "TICKET_SLA_ESCALATION",
                    "recipient_id", "admin_support"),
                true)),
        new Guardrails(new Guardrails.RateLimit(50, 60), null, null),
        "Ensures no ticket breaches SLA without escalation notification; targets 0% undetected SLA"
            + " breaches",
        "Ticket resolved before escalation (5-minute window) - duplicate escalation avoided via"
            + " dedup logic",
        4);
  }

  public static RuleSeed autoHealth() {
    return new RuleSeed(
        AUTO_HEALTH,
        "Auto-Save Play Health Drop",
        "health_score_drop",
        Map.of("below_value", 40),
        List.of(
            new ConditionSpec(
                "pharmacy.plan_tier", "in", List.of("STARTER", "RETAIL_PRO", "ENTERPRISE"))),
        List.of(
            new ActionSpec(
                "open_csm_task",
                Map.of("title", "Health score drop — save play", "priority", "HIGH"),
                false),
            new ActionSpec(
                "send_notification",
                Map.of(
                    "channel", "WHATSAPP",
                    "template_id", "PHARMACY_HEALTH_DROP",
                    "recipient_id", "pharmacy_owner"),
                true)),
        new Guardrails(new Guardrails.RateLimit(1, 10080), null, null),
        "Triggers CSM outreach for at-risk pharmacies; reduces churn by surfacing health drops"
            + " proactively",
        "Free plan pharmacies excluded (no CSM coverage); rate limit prevents spam on repeatedly"
            + " dipping pharmacies",
        5);
  }

  public static RuleSeed autoScheduleX() {
    return new RuleSeed(
        AUTO_SCHEDULE_X,
        "Auto-Flag Schedule X Sales",
        "schedule_x_sale",
        Map.of(),
        List.of(),
        List.of(
            new ActionSpec("flag_prescription", Map.of("reason", "SCHEDULE_X_SALE"), false),
            new ActionSpec(
                "send_notification",
                Map.of(
                    "channel", "WHATSAPP",
                    "template_id", "SCHEDULE_X_COMPLIANCE",
                    "recipient_id", "compliance_team"),
                true),
            new ActionSpec(
                "open_csm_task",
                Map.of("title", "Schedule X sale flagged", "priority", "HIGH"),
                true)),
        Guardrails.NONE,
        "100% Schedule X sale flagging; supports Drug Inspector audit requirement",
        "Duplicate sale events (retry storms) - dedup_window prevents double-flagging the same"
            + " sale",
        6);
  }

  public static WorkflowSeed dunningLadder() {
    ConditionSpec paid = new ConditionSpec("invoice.status", "eq", "PAID");
    return new WorkflowSeed(
        WF_DUNNING,
        "invoice_overdue",
        "Progressive dunning for overdue invoices. " + dunningImpact() + ". " + dunningEdges(),
        List.of(
            action("s1", "send_notification", Map.of("template_id", "INVOICE_OVERDUE_DAY0"), "s2"),
            wait("s2", 72, "s3"),
            branch("s3", paid, null, "s4"),
            action("s4", "send_notification", Map.of("template_id", "INVOICE_OVERDUE_DAY3"), "s5"),
            wait("s5", 96, "s6"),
            branch("s6", paid, null, "s7"),
            action("s7", "send_notification", Map.of("template_id", "INVOICE_OVERDUE_FINAL"), "s8"),
            action(
                "s8",
                "open_csm_task",
                Map.of("title", "Overdue invoice day 7 — final warning", "priority", "HIGH"),
                "s9"),
            wait("s9", 168, "s10"),
            branch("s10", paid, null, "s11"),
            action(
                "s11",
                "suspend_entity",
                Map.of("entity_type", "PHARMACY", "reason", "INVOICE_OVERDUE_D14"),
                null)));
  }

  public static WorkflowSeed pharmacyOnboarding() {
    return new WorkflowSeed(
        WF_ONBOARDING,
        "pharmacy_kyc_submitted",
        "Onboarding journey triggered after KYC submission",
        List.of(
            action(
                "s1",
                "send_notification",
                Map.of("template_id", "KYC_APPROVED", "channel", "WHATSAPP"),
                "s2"),
            wait("s2", 24, "s3"),
            action(
                "s3",
                "send_notification",
                Map.of("template_id", "ONBOARDING_SETUP_GUIDE", "channel", "EMAIL"),
                "s4"),
            wait("s4", 72, "s5"),
            branch("s5", new ConditionSpec("pharmacy.is_live", "eq", true), "s6", "s7"),
            action(
                "s6",
                "send_notification",
                Map.of("template_id", "CONGRATULATIONS_LIVE", "channel", "WHATSAPP"),
                null),
            action(
                "s7",
                "open_csm_task",
                Map.of("title", "Pharmacy not live after 3 days", "priority", "HIGH"),
                null)));
  }

  public static WorkflowSeed winBack() {
    ConditionSpec recovered = new ConditionSpec("pharmacy.usage_recovered", "eq", true);
    return new WorkflowSeed(
        WF_WIN_BACK,
        "usage_dip",
        "Win-back sequence when pharmacy usage dips below baseline",
        List.of(
            action(
                "s1",
                "send_notification",
                Map.of("template_id", "WIN_BACK_OFFER", "channel", "WHATSAPP"),
                "s2"),
            wait("s2", 48, "s3"),
            branch("s3", recovered, null, "s4"),
            action(
                "s4",
                "send_notification",
                Map.of("template_id", "WIN_BACK_REMINDER", "channel", "EMAIL"),
                "s5"),
            wait("s5", 72, "s6"),
            branch("s6", recovered, null, "s7"),
            action(
                "s7",
                "open_csm_task",
                Map.of("title", "Win-back outreach — usage still dipped", "priority", "HIGH"),
                null)));
  }

  public static String description(String impact, String edges) {
    return "Expected impact: " + impact + ". Edge cases: " + edges;
  }

  private static WorkflowStep action(
      String id, String actionId, Map<String, Object> params, String next) {
    return new WorkflowStep(id, StepType.ACTION, actionId, params, null, null, next, null);
  }

  private static WorkflowStep wait(String id, int hours, String next) {
    return new WorkflowStep(id, StepType.WAIT, null, Map.of(), hours, null, next, null);
  }

  private static WorkflowStep branch(
      String id, ConditionSpec condition, String whenTrue, String whenFalse) {
    return new WorkflowStep(
        id, StepType.BRANCH, null, Map.of(), null, condition, whenTrue, whenFalse);
  }
}
