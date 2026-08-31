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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class PosInsuranceClaimServiceTest {

  private JdbcTemplate jdbc;
  private PosInsuranceClaimService service;
  private final UUID pharmacyId = UUID.randomUUID();
  private final MedmatePrincipal owner =
      new MedmatePrincipal(
          UUID.randomUUID(), AuthRole.PHARMACY_OWNER, pharmacyId, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    jdbc = mock(JdbcTemplate.class);
    service =
        new PosInsuranceClaimService(
            jdbc, Clock.fixed(Instant.parse("2026-08-31T06:00:00Z"), ZoneOffset.UTC));
    when(jdbc.update(
            anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(1);
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
  }

  @Test
  void submitsAndReadsClaims() {
    UUID invoiceId = UUID.randomUUID();
    when(jdbc.queryForList(anyString(), any(Object.class), any(Object.class)))
        .thenReturn(List.of(Map.of("id", invoiceId, "payment_method", "INSURANCE_TPA")));
    when(jdbc.queryForList(anyString(), any(Object.class))).thenReturn(List.of());
    Map<String, Object> created = service.submit(owner, invoiceId, " Star ", " P1 ", " n ");
    assertThat(created.get("status")).isEqualTo("SUBMITTED");
    when(jdbc.queryForList(anyString(), any(Object.class))).thenReturn(List.of());
    assertThat(service.submit(owner, invoiceId, null, "  ", "").get("status"))
        .isEqualTo("SUBMITTED");
    UUID claimId = UUID.randomUUID();
    when(jdbc.queryForList(anyString(), any(Object.class)))
        .thenReturn(List.of(Map.of("id", claimId, "status", "PENDING")));
    Map<String, Object> existing = service.submit(owner, invoiceId, null, null, null);
    assertThat(existing.get("claim_id")).isEqualTo(claimId.toString());
    when(jdbc.queryForList(anyString(), any(Object.class), any(Object.class)))
        .thenReturn(
            List.of(
                Map.of(
                    "id",
                    claimId,
                    "tpa_name",
                    "Star",
                    "policy_number",
                    "P1",
                    "status",
                    "SUBMITTED",
                    "notes",
                    "n",
                    "created_at",
                    Instant.parse("2026-08-31T06:00:00Z"))));
    assertThat(service.get(owner, invoiceId).get("tpa_name")).isEqualTo("Star");
  }

  @Test
  void guardsAndValidates() {
    assertThatThrownBy(() -> service.submit(null, UUID.randomUUID(), null, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");
    MedmatePrincipal customer =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.submit(customer, UUID.randomUUID(), null, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    MedmatePrincipal noShop =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.PHARMACY_STAFF, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.submit(noShop, UUID.randomUUID(), null, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(() -> service.submit(owner, null, null, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    UUID invoiceId = UUID.randomUUID();
    when(jdbc.queryForList(anyString(), any(Object.class), any(Object.class)))
        .thenReturn(List.of());
    assertThatThrownBy(() -> service.submit(owner, invoiceId, null, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVOICE_NOT_FOUND");
    when(jdbc.queryForList(anyString(), any(Object.class), any(Object.class)))
        .thenReturn(List.of(Map.of("id", invoiceId, "payment_method", "CASH")));
    assertThatThrownBy(() -> service.submit(owner, invoiceId, null, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.get(null, invoiceId))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(() -> service.get(owner, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    when(jdbc.queryForList(anyString(), any(Object.class), any(Object.class)))
        .thenReturn(List.of());
    assertThatThrownBy(() -> service.get(owner, invoiceId))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("CLAIM_NOT_FOUND");
  }
}
