package com.nammamedmate.automation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ApprovalRouterTest {

  private static final ActionDefinition SUSPEND =
      new ActionDefinition(
          "suspend_entity", "ADMIN", "n", "d", List.of(), List.of(), true, true, null);
  private static final ActionDefinition PAYOUT =
      new ActionDefinition(
          "release_payout", "FINANCE", "n", "d", List.of(), List.of(), false, false, 5_000_000L);
  private static final ActionDefinition PLAN =
      new ActionDefinition("change_plan", "CRM", "n", "d", List.of(), List.of(), true, false, null);

  @Test
  void requireApprovalFlagAndAlwaysRequire() {
    assertThat(
            ApprovalRouter.requiresApproval(
                "auto_assign_rider",
                Map.of(),
                Map.of(),
                new Guardrails(null, null, null, true, null),
                null))
        .isTrue();
    assertThat(
            ApprovalRouter.requiresApproval(
                "suspend_entity", Map.of(), Map.of(), Guardrails.NONE, SUSPEND))
        .isTrue();
    assertThat(
            ApprovalRouter.requiresApproval(
                "mass_suspension", Map.of(), Map.of(), Guardrails.NONE, null))
        .isTrue();
    assertThat(
            ApprovalRouter.requiresApproval(
                "payout_above_1_lakh", Map.of(), Map.of(), Guardrails.NONE, null))
        .isTrue();
  }

  @Test
  void massSuspendAndPayoutCapAndValueCap() {
    assertThat(
            ApprovalRouter.requiresApproval(
                "suspend_entity",
                Map.of("entity_ids", List.of(1, 2, 3, 4, 5, 6)),
                Map.of(),
                Guardrails.NONE,
                null))
        .isTrue();
    assertThat(
            ApprovalRouter.requiresApproval(
                "suspend_entity", Map.of("entity_count", 3), Map.of(), Guardrails.NONE, null))
        .isFalse();
    assertThat(
            ApprovalRouter.requiresApproval(
                "release_payout",
                Map.of("amount_paise", 10_000_001L),
                Map.of(),
                Guardrails.NONE,
                PAYOUT))
        .isTrue();
    assertThat(
            ApprovalRouter.requiresApproval(
                "release_payout",
                Map.of("amount_paise", 6_000_000L),
                Map.of(),
                new Guardrails(null, 5_000_000L, null),
                PAYOUT))
        .isTrue();
    assertThat(
            ApprovalRouter.requiresApproval(
                "release_payout",
                Map.of("payout_amount_paise", "6000000"),
                Map.of(),
                new Guardrails(null, null, 5_000_000L),
                PAYOUT))
        .isTrue();
    assertThat(
            ApprovalRouter.requiresApproval(
                "auto_assign_rider", Map.of(), Map.of(), Guardrails.NONE, null))
        .isFalse();
  }

  @Test
  void amountFromContextPayloadAndUrgency() {
    assertThat(
            ApprovalRouter.extractAmount(Map.of(), Map.of("payload", Map.of("amount_paise", 100L))))
        .isEqualTo(100L);
    assertThat(ApprovalRouter.extractAmount(Map.of("amount", "nope"), Map.of())).isNull();
    assertThat(ApprovalRouter.extractAmount(null, null)).isNull();
    assertThat(ApprovalRouter.urgency(6_000_000L, Map.of())).isEqualTo(ApprovalUrgency.URGENT);
    assertThat(ApprovalRouter.urgency(100L, Map.of("sla_breach", true)))
        .isEqualTo(ApprovalUrgency.URGENT);
    assertThat(ApprovalRouter.urgency(100L, Map.of("trigger_id", "order_sla_breaching")))
        .isEqualTo(ApprovalUrgency.URGENT);
    assertThat(ApprovalRouter.urgency(100L, Map.of("payload", Map.of("sla_breached", "yes"))))
        .isEqualTo(ApprovalUrgency.URGENT);
    assertThat(ApprovalRouter.urgency(100L, Map.of())).isEqualTo(ApprovalUrgency.NORMAL);
    assertThat(ApprovalRouter.urgency(null, null)).isEqualTo(ApprovalUrgency.NORMAL);
  }

  @Test
  void categoryWhyImpactAndCounts() {
    assertThat(ApprovalRouter.category("release_payout", PAYOUT))
        .isEqualTo(ApprovalCategory.FINANCE);
    assertThat(ApprovalRouter.category("change_plan", PLAN)).isEqualTo(ApprovalCategory.CRM);
    assertThat(ApprovalRouter.category("open_csm_task", null)).isEqualTo(ApprovalCategory.CRM);
    assertThat(ApprovalRouter.category("suspend_entity", SUSPEND))
        .isEqualTo(ApprovalCategory.ADMIN);
    assertThat(
            ApprovalRouter.category(
                "x", new ActionDefinition("x", "CRM", "n", "d", null, null, false, false, null)))
        .isEqualTo(ApprovalCategory.CRM);
    assertThat(
            ApprovalRouter.category(
                "x",
                new ActionDefinition("x", "FINANCE", "n", "d", null, null, false, false, null)))
        .isEqualTo(ApprovalCategory.FINANCE);
    assertThat(ApprovalRouter.category("x", null)).isEqualTo(ApprovalCategory.ADMIN);

    assertThat(
            ApprovalRouter.why(
                "suspend_entity", null, Map.of("count", 9), Map.of(), Guardrails.NONE, null))
        .contains("Mass suspension");
    assertThat(
            ApprovalRouter.why(
                "release_payout", 11_000_000L, Map.of(), Map.of(), Guardrails.NONE, PAYOUT))
        .contains("1,00,000");
    assertThat(
            ApprovalRouter.why(
                "payout_above_1_lakh", null, Map.of(), Map.of(), Guardrails.NONE, null))
        .contains("ALWAYS_REQUIRE");
    assertThat(
            ApprovalRouter.why(
                "suspend_entity", null, Map.of(), Map.of(), Guardrails.NONE, SUSPEND))
        .contains("always requires");
    assertThat(
            ApprovalRouter.why(
                "x",
                null,
                Map.of(),
                Map.of(),
                new Guardrails(null, null, null, true, "open_csm_task"),
                null))
        .contains("require_approval");
    assertThat(
            ApprovalRouter.why(
                "release_payout",
                6_000_000L,
                Map.of(),
                Map.of(),
                new Guardrails(null, 5_000_000L, null),
                PAYOUT))
        .contains("value cap");
    assertThat(
            ApprovalRouter.why(
                "release_payout",
                6_000_000L,
                Map.of(),
                Map.of(),
                new Guardrails(null, null, 5_000_000L),
                PAYOUT))
        .contains("require_approval_above");
    assertThat(ApprovalRouter.why("x", null, Map.of(), Map.of(), Guardrails.NONE, null))
        .contains("human approval");

    assertThat(ApprovalRouter.estimatedImpact("release_payout", "PHARMACY", "Apollo", 4_800_000L))
        .contains("Rs 48000");
    assertThat(ApprovalRouter.estimatedImpact("suspend_entity", "RIDER", "Suresh", null))
        .contains("Suspend");
    assertThat(ApprovalRouter.estimatedImpact("open_csm_task", "PHARMACY", null, null))
        .contains("Execute");
    assertThat(ApprovalRouter.rupees(4_800_000L)).isEqualTo(48000L);

    assertThat(ApprovalRouter.entityCount(Map.of("entity_ids", new Object[] {1, 2}), Map.of()))
        .isEqualTo(2);
    assertThat(ApprovalRouter.entityCount(null, Map.of("entity_count", 4))).isEqualTo(4);
    assertThat(ApprovalRouter.entityCount(null, null)).isEqualTo(1);
    assertThat(ApprovalRouter.isSlaBreach(Map.of("trigger_event", "order_sla_breaching"))).isTrue();
    assertThat(ApprovalRouter.isSlaBreach(Map.of("sla_breach", 1))).isTrue();
    assertThat(ApprovalRouter.isSlaBreach(Map.of())).isFalse();
    UUID id = UUID.fromString("11111111-1111-4111-8111-111111111111");
    assertThat(ApprovalRouter.parseUuid(id)).isEqualTo(id);
    assertThat(ApprovalRouter.parseUuid(id.toString())).isEqualTo(id);
    assertThat(ApprovalRouter.parseUuid("nope")).isNull();
    assertThat(ApprovalRouter.parseUuid(null)).isNull();
    assertThat(ApprovalRouter.stringVal(null)).isNull();
    assertThat(ApprovalRouter.stringVal("  ")).isNull();
    assertThat(ApprovalRouter.stringVal("null")).isNull();
    assertThat(ApprovalRouter.stringVal("Apollo")).isEqualTo("Apollo");
  }

  @Test
  void enumsParse() {
    assertThat(ApprovalStatus.parse(" pending ")).isEqualTo(ApprovalStatus.PENDING);
    assertThat(ApprovalUrgency.parse("urgent")).isEqualTo(ApprovalUrgency.URGENT);
    assertThat(ApprovalCategory.parse(null)).isEqualTo(ApprovalCategory.ADMIN);
    assertThat(ApprovalCategory.parse(" ")).isEqualTo(ApprovalCategory.ADMIN);
    assertThat(ApprovalCategory.parse("nope")).isEqualTo(ApprovalCategory.ADMIN);
    assertThat(ApprovalCategory.parse("crm")).isEqualTo(ApprovalCategory.CRM);
    assertThatThrownBy(() -> ApprovalStatus.parse("")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ApprovalUrgency.parse(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(
            new AutomationApproval(
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null)
                .status())
        .isEqualTo(ApprovalStatus.PENDING);
  }

  @Test
  void guardrailsNewFields() {
    Guardrails g =
        Guardrails.fromMap(Map.of("require_approval", "true", "on_reject_action", "open_csm_task"));
    assertThat(g.requireApproval()).isTrue();
    assertThat(g.onRejectAction()).isEqualTo("open_csm_task");
    assertThat(g.toMap()).containsEntry("require_approval", true);
    assertThat(
            Guardrails.fromMap(Map.of("require_approval", true, "on_reject_action", "  "))
                .onRejectAction())
        .isNull();
    assertThat(Guardrails.fromMap(Map.of("require_approval", false)).requireApproval()).isFalse();
  }
}
