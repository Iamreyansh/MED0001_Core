package com.nammamedmate.catalogue.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScheduleRulesTest {

  @Test
  void all_containsOtcH_H1_X() {
    List<Map<String, Object>> all = ScheduleRules.all();
    assertThat(all).hasSize(4);
    assertThat(all.get(0))
        .containsEntry("schedule", "OTC")
        .containsEntry("prescription_required", false)
        .containsEntry("online_delivery_allowed", true);
    assertThat(all.get(1))
        .containsEntry("schedule", "H")
        .containsEntry("online_delivery_allowed", true);
    assertThat(all.get(2))
        .containsEntry("schedule", "H1")
        .containsKey("register_name")
        .containsEntry("online_delivery_allowed", true);
    assertThat(all.get(3))
        .containsEntry("schedule", "X")
        .containsEntry("prescription_type", "TRIPLICATE")
        .containsEntry("patient_id_verification", true)
        .containsEntry("online_delivery_allowed", false);
  }
}
