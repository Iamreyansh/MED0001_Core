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

class PosReturnServiceTest {

  private JdbcTemplate jdbc;
  private PosReturnService service;
  private final UUID pharmacyId = UUID.randomUUID();
  private final MedmatePrincipal owner =
      new MedmatePrincipal(
          UUID.randomUUID(), AuthRole.PHARMACY_OWNER, pharmacyId, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    jdbc = mock(JdbcTemplate.class);
    service =
        new PosReturnService(
            jdbc, Clock.fixed(Instant.parse("2026-08-31T06:00:00Z"), ZoneOffset.UTC));
    stubUpdates();
  }

  @Test
  void createsCreditNoteAndRestocks() {
    UUID invoiceId = UUID.randomUUID();
    UUID itemId = UUID.randomUUID();
    UUID productId = UUID.randomUUID();
    UUID batchId = UUID.randomUUID();
    when(jdbc.queryForList(anyString(), any(Object.class), any(Object.class)))
        .thenAnswer(
            inv -> {
              String sql = inv.getArgument(0);
              if (sql.contains("FROM invoice ")) {
                return List.of(Map.of("id", invoiceId, "status", "PAID"));
              }
              return List.of(
                  Map.of(
                      "id",
                      itemId,
                      "product_id",
                      productId,
                      "batch_id",
                      batchId,
                      "quantity",
                      2,
                      "line_total_paise",
                      200L));
            });
    Map<String, Object> data =
        service.createReturn(
            owner, invoiceId, "Damaged", List.of(new PosReturnService.ReturnLine(itemId, 1)));
    assertThat(data.get("invoice_id")).isEqualTo(invoiceId.toString());
    assertThat(data.get("total_paise")).isEqualTo(100L);
  }

  @Test
  void guardsAndValidates() {
    assertThatThrownBy(() -> service.createReturn(null, UUID.randomUUID(), "r", List.of()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");
    MedmatePrincipal customer =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.createReturn(customer, UUID.randomUUID(), "r", List.of()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    MedmatePrincipal noShop =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.PHARMACY_STAFF, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.createReturn(noShop, UUID.randomUUID(), "r", List.of()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(() -> service.createReturn(owner, null, "r", List.of()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.createReturn(owner, UUID.randomUUID(), "  ", List.of()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.createReturn(owner, UUID.randomUUID(), null, List.of()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.createReturn(owner, UUID.randomUUID(), "r", List.of()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.createReturn(owner, UUID.randomUUID(), "r", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    UUID invoiceId = UUID.randomUUID();
    when(jdbc.queryForList(anyString(), any(Object.class), any(Object.class)))
        .thenReturn(List.of());
    assertThatThrownBy(
            () ->
                service.createReturn(
                    owner,
                    invoiceId,
                    "r",
                    List.of(new PosReturnService.ReturnLine(UUID.randomUUID(), 1))))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVOICE_NOT_FOUND");
    when(jdbc.queryForList(anyString(), any(Object.class), any(Object.class)))
        .thenReturn(List.of(Map.of("id", invoiceId, "status", "CREDIT_NOTE_ISSUED")));
    assertThatThrownBy(
            () ->
                service.createReturn(
                    owner,
                    invoiceId,
                    "r",
                    List.of(new PosReturnService.ReturnLine(UUID.randomUUID(), 1))))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVOICE_IMMUTABLE");
    when(jdbc.queryForList(anyString(), any(Object.class), any(Object.class)))
        .thenAnswer(
            inv -> {
              String sql = inv.getArgument(0);
              if (sql.contains("FROM invoice ")) {
                return List.of(Map.of("id", invoiceId, "status", "PAID"));
              }
              return List.of();
            });
    assertThatThrownBy(
            () ->
                service.createReturn(
                    owner, invoiceId, "r", List.of(new PosReturnService.ReturnLine(null, 1))))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.createReturn(
                    owner, invoiceId, "r", java.util.Collections.singletonList(null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.createReturn(
                    owner,
                    invoiceId,
                    "r",
                    List.of(new PosReturnService.ReturnLine(UUID.randomUUID(), 0))))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.createReturn(
                    owner,
                    invoiceId,
                    "r",
                    List.of(new PosReturnService.ReturnLine(UUID.randomUUID(), 1))))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVOICE_ITEM_NOT_FOUND");
    UUID itemId = UUID.randomUUID();
    when(jdbc.queryForList(anyString(), any(Object.class), any(Object.class)))
        .thenAnswer(
            inv -> {
              String sql = inv.getArgument(0);
              if (sql.contains("FROM invoice ")) {
                return List.of(Map.of("id", invoiceId, "status", "PAID"));
              }
              return List.of(
                  java.util.Map.of(
                      "id",
                      itemId,
                      "product_id",
                      UUID.randomUUID(),
                      "quantity",
                      1,
                      "line_total_paise",
                      50L));
            });
    assertThatThrownBy(
            () ->
                service.createReturn(
                    owner, invoiceId, "r", List.of(new PosReturnService.ReturnLine(itemId, 2))))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    Map<String, Object> noBatch =
        service.createReturn(
            owner, invoiceId, "r", List.of(new PosReturnService.ReturnLine(itemId, 1)));
    assertThat(noBatch.get("total_paise")).isEqualTo(50L);
  }

  private void stubUpdates() {
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(1);
    when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
    when(jdbc.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);
    when(jdbc.update(anyString(), any(), any(), any(), any())).thenReturn(1);
    when(jdbc.update(anyString(), any(), any(), any())).thenReturn(1);
    when(jdbc.update(anyString(), any(), any())).thenReturn(1);
  }
}
