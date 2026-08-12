package com.nammamedmate.automation.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import org.junit.jupiter.api.Test;

class SeedDefinitionsTest {

  @Test
  void specsAreInactiveReadyAndValid() throws Exception {
    assertThat(SeedDefinitions.ruleSeeds()).hasSize(5);
    assertThat(SeedDefinitions.workflowSeeds()).hasSize(3);
    assertThat(SeedDefinitions.autoScheduleX().conditions()).isEmpty();
    assertThat(SeedDefinitions.autoPayout().guardrails().valueCap())
        .isEqualTo(SeedDefinitions.PAYOUT_CAP_PAISE);
    assertThat(SeedDefinitions.autoPayout().guardrails().requireApprovalAbove())
        .isEqualTo(5_000_000L);
    assertThat(SeedDefinitions.TICKET_SLA_TRIGGER).isEqualTo("support_sla_breaching");
    for (SeedDefinitions.WorkflowSeed wf : SeedDefinitions.workflowSeeds()) {
      WorkflowStepValidator.validate(wf.steps());
    }
    assertThat(SeedDefinitions.description("a", "b")).contains("Expected impact");
    assertThat(
            new SeedDefinitions.RuleSeed(
                    "k", "n", "t", null, null, null, Guardrails.NONE, "i", "e", 1)
                .conditions())
        .isEmpty();
    assertThat(new SeedDefinitions.WorkflowSeed("k", "t", "d", null).steps()).isEmpty();
    Constructor<SeedDefinitions> ctor = SeedDefinitions.class.getDeclaredConstructor();
    ctor.setAccessible(true);
    assertThat(ctor.newInstance()).isNotNull();
  }
}
