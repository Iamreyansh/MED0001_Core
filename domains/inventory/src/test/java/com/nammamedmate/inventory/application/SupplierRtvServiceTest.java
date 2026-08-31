package com.nammamedmate.inventory.application;

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

class SupplierRtvServiceTest {

  private JdbcTemplate jdbc;
  private SupplierRtvService service;
  private final UUID pharmacyId = UUID.randomUUID();
  private final MedmatePrincipal owner =
      new MedmatePrincipal(
          UUID.randomUUID(), AuthRole.PHARMACY_OWNER, pharmacyId, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    jdbc = mock(JdbcTemplate.class);
    service =
        new SupplierRtvService(
            jdbc, Clock.fixed(Instant.parse("2026-08-31T06:00:00Z"), ZoneOffset.UTC));
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(1);
    when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
    when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any())).thenReturn(1);
    when(jdbc.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);
    when(jdbc.update(anyString(), any(), any(), any(), any())).thenReturn(1);
    when(jdbc.update(anyString(), any(), any(), any())).thenReturn(1);
  }

  @Test
  void postsRtvAndDecrementsStock() {
    UUID grnId = UUID.randomUUID();
    UUID itemId = UUID.randomUUID();
    UUID productId = UUID.randomUUID();
    UUID batchId = UUID.randomUUID();
    when(jdbc.queryForList(anyString(), any(Object.class), any(Object.class)))
        .thenAnswer(
            inv -> {
              String sql = inv.getArgument(0);
              if (sql.contains("FROM purchase_grn ")) {
                return List.of(Map.of("id", grnId, "status", "STOCKED"));
              }
              if (sql.contains("FROM product_batch")) {
                return List.of(Map.of("id", batchId));
              }
              return List.of();
            });
    when(jdbc.queryForList(anyString(), any(Object.class), any(Object.class), any(Object.class)))
        .thenReturn(
            List.of(
                Map.of(
                    "id",
                    itemId,
                    "product_id",
                    productId,
                    "batch_number",
                    "B1",
                    "quantity",
                    5,
                    "free_quantity",
                    1)));
    Map<String, Object> data =
        service.create(owner, grnId, "Expired", List.of(new SupplierRtvService.RtvLine(itemId, 2)));
    assertThat(data.get("grn_id")).isEqualTo(grnId.toString());
  }

  @Test
  void guardsAndValidates() {
    assertThatThrownBy(() -> service.create(null, UUID.randomUUID(), "r", List.of()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");
    MedmatePrincipal customer =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.create(customer, UUID.randomUUID(), "r", List.of()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    MedmatePrincipal noShop =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.PHARMACY_STAFF, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.create(noShop, UUID.randomUUID(), "r", List.of()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(() -> service.create(owner, null, "r", List.of()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.create(owner, UUID.randomUUID(), " ", List.of()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.create(owner, UUID.randomUUID(), null, List.of()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.create(owner, UUID.randomUUID(), "r", List.of()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.create(owner, UUID.randomUUID(), "r", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    UUID grnId = UUID.randomUUID();
    when(jdbc.queryForList(anyString(), any(Object.class), any(Object.class)))
        .thenReturn(List.of());
    assertThatThrownBy(
            () ->
                service.create(
                    owner,
                    grnId,
                    "r",
                    List.of(new SupplierRtvService.RtvLine(UUID.randomUUID(), 1))))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("GRN_NOT_FOUND");
    when(jdbc.queryForList(anyString(), any(Object.class), any(Object.class)))
        .thenReturn(List.of(Map.of("id", grnId, "status", "DRAFT")));
    assertThatThrownBy(
            () ->
                service.create(
                    owner,
                    grnId,
                    "r",
                    List.of(new SupplierRtvService.RtvLine(UUID.randomUUID(), 1))))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    when(jdbc.queryForList(anyString(), any(Object.class), any(Object.class)))
        .thenReturn(List.of(Map.of("id", grnId, "status", "STOCKED")));
    assertThatThrownBy(
            () ->
                service.create(owner, grnId, "r", List.of(new SupplierRtvService.RtvLine(null, 1))))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () -> service.create(owner, grnId, "r", java.util.Collections.singletonList(null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.create(
                    owner,
                    grnId,
                    "r",
                    List.of(new SupplierRtvService.RtvLine(UUID.randomUUID(), 0))))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    when(jdbc.queryForList(anyString(), any(Object.class), any(Object.class), any(Object.class)))
        .thenReturn(List.of());
    assertThatThrownBy(
            () ->
                service.create(
                    owner,
                    grnId,
                    "r",
                    List.of(new SupplierRtvService.RtvLine(UUID.randomUUID(), 1))))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("GRN_ITEM_NOT_FOUND");
    UUID itemId = UUID.randomUUID();
    when(jdbc.queryForList(anyString(), any(Object.class), any(Object.class), any(Object.class)))
        .thenReturn(
            List.of(
                Map.of(
                    "id",
                    itemId,
                    "product_id",
                    UUID.randomUUID(),
                    "batch_number",
                    "B1",
                    "quantity",
                    1,
                    "free_quantity",
                    0)));
    assertThatThrownBy(
            () ->
                service.create(
                    owner, grnId, "r", List.of(new SupplierRtvService.RtvLine(itemId, 4))))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    when(jdbc.queryForList(anyString(), any(Object.class), any(Object.class)))
        .thenAnswer(
            inv -> {
              String sql = inv.getArgument(0);
              if (sql.contains("FROM purchase_grn ")) {
                return List.of(Map.of("id", grnId, "status", "STOCKED"));
              }
              return List.of(Map.of("id", UUID.randomUUID()));
            });
    when(jdbc.update(anyString(), any(), any(), any(), any(), any())).thenReturn(0);
    assertThatThrownBy(
            () ->
                service.create(
                    owner, grnId, "r", List.of(new SupplierRtvService.RtvLine(itemId, 1))))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INSUFFICIENT_STOCK");
    when(jdbc.queryForList(anyString(), any(Object.class), any(Object.class), any(Object.class)))
        .thenAnswer(
            inv -> {
              String sql = inv.getArgument(0);
              if (sql.contains("FROM purchase_grn_item")) {
                return List.of(
                    Map.of(
                        "id",
                        itemId,
                        "product_id",
                        UUID.randomUUID(),
                        "batch_number",
                        "B1",
                        "quantity",
                        1,
                        "free_quantity",
                        0));
              }
              return List.of();
            });
    when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
    Map<String, Object> noBatch =
        service.create(owner, grnId, "r", List.of(new SupplierRtvService.RtvLine(itemId, 1)));
    assertThat(noBatch.get("grn_id")).isEqualTo(grnId.toString());
  }
}
