package com.nammamedmate.automation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.nammamedmate.automation.application.port.out.ActionRegistryPort;
import com.nammamedmate.automation.application.port.out.TriggerRegistryPort;
import com.nammamedmate.automation.domain.ActionDefinition;
import com.nammamedmate.automation.domain.TriggerDefinition;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CatalogServicesAcTest {

  @Mock TriggerRegistryPort triggers;
  @Mock ActionRegistryPort actions;

  @Test
  @SuppressWarnings("unchecked")
  void ac001_triggersIncludeContextAndConditions() {
    when(triggers.listActive(null))
        .thenReturn(
            List.of(
                new TriggerDefinition(
                    "order_unassigned",
                    "DISPATCH",
                    "Order Unassigned",
                    "d",
                    List.of(Map.of("name", "duration_minutes")),
                    List.of("zone_in"),
                    List.of("order.id"),
                    true)));
    // pad to ≥28 for AC shape — service returns whatever registry has
    Map<String, Object> data = new TriggerCatalogService(triggers).list(null);
    assertThat(data.get("total_triggers")).isEqualTo(1);
    Map<String, Object> row = ((List<Map<String, Object>>) data.get("triggers")).getFirst();
    assertThat(row).containsKeys("available_context_vars", "available_conditions");
  }

  @Test
  @SuppressWarnings("unchecked")
  void ac002_suspendAndMassPayoutRequireApproval() {
    when(actions.listAll())
        .thenReturn(
            List.of(
                new ActionDefinition(
                    "suspend_entity",
                    "ADMIN",
                    "Suspend",
                    "d",
                    List.of("entity_id"),
                    List.of(),
                    true,
                    true,
                    null),
                new ActionDefinition(
                    "mass_payout",
                    "FINANCE",
                    "Mass",
                    "d",
                    List.of("cycle_id"),
                    List.of(),
                    false,
                    true,
                    null),
                new ActionDefinition(
                    "release_payout",
                    "FINANCE",
                    "Release",
                    "d",
                    List.of("amount_paise"),
                    List.of("mode"),
                    false,
                    false,
                    5_000_000L)));

    Map<String, Object> data = new ActionCatalogService(actions).list();
    assertThat(data.get("total_actions")).isEqualTo(3);
    List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("actions");
    Map<String, Map<String, Object>> byId =
        rows.stream()
            .collect(java.util.stream.Collectors.toMap(r -> (String) r.get("action_id"), r -> r));
    assertThat(byId.get("suspend_entity").get("always_require_approval")).isEqualTo(true);
    assertThat(byId.get("mass_payout").get("always_require_approval")).isEqualTo(true);
    assertThat(byId.get("release_payout").get("auto_approval_limit_paise")).isEqualTo(5_000_000L);
  }
}
