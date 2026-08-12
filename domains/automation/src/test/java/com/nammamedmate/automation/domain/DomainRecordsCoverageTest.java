package com.nammamedmate.automation.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.nammamedmate.automation.adapter.in.web.InternalRulesEvaluateController;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DomainRecordsCoverageTest {

  @Test
  void nullCollectionsBecomeEmptyOrStayNull() {
    assertThat(new TriggerDefinition("t", "ORDERS", "n", "d", null, null, null, true).parameters())
        .isEmpty();
    assertThat(
            new ActionDefinition("a", "ADMIN", "n", "d", null, null, false, false, null)
                .requiredParams())
        .isEmpty();
    assertThat(new ConditionEvaluator.EvalResult(true, null).evaluated()).isEmpty();
    assertThat(new InternalRulesEvaluateController.ActionDto("x", null, null).params()).isNull();
    assertThat(
            new InternalRulesEvaluateController.EvaluateRequest(
                    UUID.randomUUID(), null, true, null, null, null)
                .conditions())
        .isNull();
    assertThat(
            new InternalRulesEvaluateController.EventDto("t", "ORDER", null, null, null).payload())
        .isNull();
    assertThat(
            new InternalRulesEvaluateController.EvaluateRequest(
                    null,
                    null,
                    false,
                    List.of(new InternalRulesEvaluateController.ConditionDto("a", "eq", 1)),
                    List.of(new InternalRulesEvaluateController.ActionDto("a", Map.of(), false)),
                    1)
                .actions())
        .hasSize(1);
  }
}
