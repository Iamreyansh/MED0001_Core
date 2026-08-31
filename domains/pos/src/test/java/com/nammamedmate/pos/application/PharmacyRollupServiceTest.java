package com.nammamedmate.pos.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class PharmacyRollupServiceTest {

  private JdbcTemplate jdbc;
  private PharmacyRollupService service;
  private final UUID pharmacyId = UUID.randomUUID();
  private final MedmatePrincipal owner =
      new MedmatePrincipal(
          UUID.randomUUID(), AuthRole.PHARMACY_OWNER, pharmacyId, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    jdbc = mock(JdbcTemplate.class);
    service = new PharmacyRollupService(jdbc);
  }

  @Test
  void singleShopAndGrowthMultiBranch() {
    when(jdbc.queryForList(anyString(), any(Object.class)))
        .thenAnswer(
            inv -> {
              String sql = inv.getArgument(0);
              if (sql.contains("pharmacy_staff_assignment")) {
                return List.of();
              }
              return List.of(Map.of("invoices", 2L, "revenue_paise", 500L));
            });
    Map<String, Object> alone = service.summary(owner);
    assertThat(alone.get("pharmacy_count")).isEqualTo(1);
    UUID other = UUID.randomUUID();
    when(jdbc.queryForList(anyString(), any(Object.class)))
        .thenAnswer(
            inv -> {
              String sql = inv.getArgument(0);
              if (sql.contains("pharmacy_staff_assignment")) {
                java.util.Map<String, Object> unnamed = new java.util.LinkedHashMap<>();
                unnamed.put("pharmacy_id", other);
                unnamed.put("name", null);
                unnamed.put("subscription_plan", "FREE");
                return List.of(
                    Map.of(
                        "pharmacy_id",
                        pharmacyId,
                        "name",
                        "Main",
                        "subscription_plan",
                        "RETAIL_PRO"),
                    unnamed);
              }
              return List.of(Map.of("invoices", 2L, "revenue_paise", 500L));
            });
    Map<String, Object> growth = service.summary(owner);
    assertThat(growth.get("pharmacy_count")).isEqualTo(2);
    assertThat(PharmacyRollupService.rollupPlan("GROWTH")).isTrue();
    assertThat(PharmacyRollupService.rollupPlan("ENTERPRISE")).isTrue();
    assertThat(PharmacyRollupService.rollupPlan("RETAIL_PRO")).isTrue();
    assertThat(PharmacyRollupService.rollupPlan("FREE")).isFalse();
  }

  @Test
  void freePlanBlocksMultiBranch() {
    UUID other = UUID.randomUUID();
    when(jdbc.queryForList(anyString(), any(Object.class)))
        .thenReturn(
            List.of(
                Map.of("pharmacy_id", pharmacyId, "name", "Main", "subscription_plan", "FREE"),
                Map.of("pharmacy_id", other, "name", "Branch", "subscription_plan", "FREE")));
    assertThatThrownBy(() -> service.summary(owner))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PLAN_UPGRADE_REQUIRED");
  }

  @Test
  void guards() {
    when(jdbc.queryForList(anyString(), any(Object.class))).thenReturn(List.of());
    assertThatThrownBy(() -> service.summary(null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");
    MedmatePrincipal customer =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.summary(customer))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    MedmatePrincipal noShop =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.PHARMACY_STAFF, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.summary(noShop))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");
    MedmatePrincipal staff =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.PHARMACY_STAFF, pharmacyId, TokenScope.FULL, "j");
    assertThat(service.summary(staff).get("invoices")).isEqualTo(0L);
    assertThat(service.summary(owner).get("invoices")).isEqualTo(0L);
  }
}
