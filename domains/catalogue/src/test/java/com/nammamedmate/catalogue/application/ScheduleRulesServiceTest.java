package com.nammamedmate.catalogue.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.InMemoryRateLimiter;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ScheduleRulesServiceTest {

  private ScheduleRulesService service;
  private final MedmatePrincipal owner =
      new MedmatePrincipal(
          UUID.randomUUID(), AuthRole.PHARMACY_OWNER, UUID.randomUUID(), TokenScope.FULL, "j");
  private final MedmatePrincipal customer =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    service =
        new ScheduleRulesService(
            new InMemoryRateLimiter(
                Clock.fixed(Instant.parse("2026-08-08T00:00:00Z"), ZoneOffset.UTC)));
  }

  @Test
  void get_returnsFourSchedulesWithFlags() {
    Map<String, Object> data = service.get(owner);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> schedules = (List<Map<String, Object>>) data.get("schedules");
    assertThat(schedules).hasSize(4);
    assertThat(schedules.stream().map(s -> s.get("schedule")).toList())
        .containsExactly("OTC", "H", "H1", "X");
    Map<String, Object> x =
        schedules.stream().filter(s -> "X".equals(s.get("schedule"))).findFirst().orElseThrow();
    assertThat(x)
        .containsEntry("prescription_required", true)
        .containsEntry("special_register_required", true)
        .containsEntry("online_delivery_allowed", false)
        .containsKey("regulatory_reference");
  }

  @Test
  void get_forbiddenForCustomer() {
    assertThatThrownBy(() -> service.get(customer))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void get_unauthorizedWhenNull() {
    assertThatThrownBy(() -> service.get(null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
  }
}
