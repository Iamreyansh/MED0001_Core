package com.nammamedmate.settings.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.InMemoryRateLimiter;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import com.nammamedmate.settings.application.port.out.AdminAuditAppendPort;
import com.nammamedmate.settings.application.port.out.PlatformConfigCachePort;
import com.nammamedmate.settings.application.port.out.PlatformConfigStore;
import com.nammamedmate.settings.application.port.out.PlatformConfigStore.ConfigRow;
import com.nammamedmate.settings.application.port.out.PlatformConfigStore.HistoryRow;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlatformConfigServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-24T02:00:00Z");

  private PlatformConfigStore store;
  private PlatformConfigCachePort cache;
  private RecordingAudit audit;
  private InMemoryRateLimiter rateLimiter;
  private PlatformConfigService service;
  private MedmatePrincipal superAdmin;
  private MedmatePrincipal opsAdmin;

  @BeforeEach
  void setUp() {
    store = mock(PlatformConfigStore.class);
    cache = mock(PlatformConfigCachePort.class);
    audit = new RecordingAudit();
    rateLimiter = new InMemoryRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC));
    service =
        new PlatformConfigService(
            store, cache, audit, rateLimiter, Clock.fixed(NOW, ZoneOffset.UTC), "production");
    superAdmin =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
    opsAdmin =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j2");
  }

  @Test
  void ac1_patchDeliveryFeeUpdatesDbInvalidatesCacheAndWritesHistory() {
    ConfigRow fee = row("orders.delivery_fee", "25", "integer", "INR", "orders", false);
    when(cache.getAll()).thenReturn(Optional.of(List.of(fee)));

    Map<String, Object> data = service.bulkUpdate(superAdmin, Map.of("orders.delivery_fee", 30));

    assertThat(data)
        .containsEntry("updated_count", 1)
        .containsEntry("cache_invalidated", true)
        .containsEntry("effective_at", NOW);
    @SuppressWarnings("unchecked")
    List<String> updatedKeys = (List<String>) data.get("updated_keys");
    assertThat(updatedKeys).containsExactly("orders.delivery_fee");
    verify(store).updateValue("orders.delivery_fee", "30", superAdmin.subject(), NOW);
    verify(store)
        .insertHistory(
            any(UUID.class),
            eq("orders.delivery_fee"),
            eq("25"),
            eq("30"),
            eq(superAdmin.subject()),
            eq(NOW),
            isNull());
    verify(cache).invalidate();
    assertThat(audit.actions).containsExactly("platform_config.updated");
  }

  @Test
  void ac2_opsPatchForbidden() {
    assertThatThrownBy(() -> service.bulkUpdate(opsAdmin, Map.of("orders.delivery_fee", 30)))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    verify(store, never()).updateValue(any(), any(), any(), any());
  }

  @Test
  void ac3_wrongTypeValidationError() {
    ConfigRow fee = row("orders.delivery_fee", "25", "integer", "INR", "orders", false);
    when(cache.getAll()).thenReturn(Optional.of(List.of(fee)));

    assertThatThrownBy(
            () -> service.bulkUpdate(superAdmin, Map.of("orders.delivery_fee", "thirty")))
        .isInstanceOf(AppException.class)
        .satisfies(
            ex -> {
              AppException ae = (AppException) ex;
              assertThat(ae.code()).isEqualTo("VALIDATION_ERROR");
              assertThat(ae.getMessage()).contains("delivery_fee");
              assertThat(ae.getMessage()).contains("integer");
            });
    verify(store, never()).updateValue(any(), any(), any(), any());
  }

  @Test
  void ac4_batchWithImmutableInProdRejectsAll() {
    ConfigRow fee = row("orders.delivery_fee", "25", "integer", "INR", "orders", false);
    ConfigRow min = row("orders.min_order_value", "49", "integer", "INR", "orders", false);
    ConfigRow cod = row("payments.cod_available", "true", "boolean", null, "payments", false);
    ConfigRow prefix = row("orders.order_id_prefix", "NMM", "string", null, "orders", true);
    when(cache.getAll()).thenReturn(Optional.of(List.of(fee, min, cod, prefix)));

    Map<String, Object> batch = new LinkedHashMap<>();
    batch.put("orders.delivery_fee", 30);
    batch.put("orders.min_order_value", 99);
    batch.put("payments.cod_available", false);
    batch.put("orders.order_id_prefix", "XXX");

    assertThatThrownBy(() -> service.bulkUpdate(superAdmin, batch))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CONFIG_KEY_IMMUTABLE");
    verify(store, never()).updateValue(any(), any(), any(), any());
  }

  @Test
  void ac5_getKeyReturnsValueTypeUnitHistory() {
    ConfigRow fee = row("orders.delivery_fee", "25", "integer", "INR", "orders", false);
    when(cache.getAll()).thenReturn(Optional.of(List.of(fee)));
    UUID changer = Ids.newId();
    when(store.listHistory("orders.delivery_fee"))
        .thenReturn(
            List.of(
                new HistoryRow(
                    Ids.newId(),
                    "orders.delivery_fee",
                    "20",
                    "25",
                    changer,
                    "Ayesha Siddiqui",
                    NOW,
                    "notes")));

    Map<String, Object> data = service.get(superAdmin, "orders.delivery_fee");
    assertThat(data)
        .containsEntry("key", "orders.delivery_fee")
        .containsEntry("value", 25)
        .containsEntry("type", "integer")
        .containsEntry("unit", "INR");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> history = (List<Map<String, Object>>) data.get("history");
    assertThat(history).hasSize(1);
    assertThat(history.get(0)).containsEntry("old_value", 20).containsEntry("new_value", 25);
  }

  @Test
  void ac6_coldRedisRefillsFromDb() {
    when(cache.getAll())
        .thenReturn(Optional.empty())
        .thenReturn(
            Optional.of(
                List.of(row("orders.delivery_fee", "25", "integer", "INR", "orders", false))));
    ConfigRow fee = row("orders.delivery_fee", "25", "integer", "INR", "orders", false);
    when(store.listAll()).thenReturn(List.of(fee));

    assertThat(service.getTyped("orders.delivery_fee")).contains(25);
    verify(cache).putAll(List.of(fee));
    assertThat(service.getRaw("orders.delivery_fee")).contains("25");
  }

  @Test
  void ac7_domainPaymentsFilter() {
    when(cache.getAll())
        .thenReturn(
            Optional.of(
                List.of(
                    row("orders.delivery_fee", "25", "integer", "INR", "orders", false),
                    row("payments.cod_available", "true", "boolean", null, "payments", false),
                    row(
                        "payments.refund_window_days",
                        "7",
                        "integer",
                        "days",
                        "payments",
                        false))));

    Map<String, Object> data = service.list(superAdmin, "payments");
    assertThat(data).containsOnlyKeys("payments");
    @SuppressWarnings("unchecked")
    Map<String, Object> payments = (Map<String, Object>) data.get("payments");
    assertThat(payments).containsKeys("cod_available", "refund_window_days");
  }

  @Test
  void authValidationMissingKeyAndStagingImmutableAllowed() {
    assertThatThrownBy(() -> service.list(null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(
            () ->
                service.list(
                    new MedmatePrincipal(
                        Ids.newId(), AuthRole.CUSTOMER, null, TokenScope.FULL, "c"),
                    null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.list(superAdmin, "nope"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.bulkUpdate(superAdmin, Map.of()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.bulkUpdate(superAdmin, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    when(cache.getAll()).thenReturn(Optional.of(List.of()));
    when(store.findByKey("missing.key")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.get(superAdmin, "missing.key"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CONFIG_KEY_NOT_FOUND");

    when(cache.getAll())
        .thenReturn(
            Optional.of(
                List.of(row("orders.delivery_fee", "25", "integer", "INR", "orders", false))));
    assertThatThrownBy(() -> service.bulkUpdate(superAdmin, Map.of("orders.nope", 1)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CONFIG_KEY_NOT_FOUND");

    PlatformConfigService staging =
        new PlatformConfigService(
            store, cache, audit, rateLimiter, Clock.fixed(NOW, ZoneOffset.UTC), "staging");
    ConfigRow prefix = row("orders.order_id_prefix", "NMM", "string", null, "orders", true);
    when(cache.getAll()).thenReturn(Optional.of(List.of(prefix)));
    Map<String, Object> updated =
        staging.bulkUpdate(superAdmin, Map.of("orders.order_id_prefix", "ZZZ"));
    assertThat(updated).containsEntry("updated_count", 1);

    PlatformConfigService blankEnv =
        new PlatformConfigService(
            store, cache, audit, rateLimiter, Clock.fixed(NOW, ZoneOffset.UTC), "  ");
    when(cache.getAll()).thenReturn(Optional.of(List.of(prefix)));
    assertThatThrownBy(
            () -> blankEnv.bulkUpdate(superAdmin, Map.of("orders.order_id_prefix", "ZZZ")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CONFIG_KEY_IMMUTABLE");

    PlatformConfigService nullEnv =
        new PlatformConfigService(
            store, cache, audit, rateLimiter, Clock.fixed(NOW, ZoneOffset.UTC), null);
    when(cache.getAll()).thenReturn(Optional.of(List.of(prefix)));
    assertThatThrownBy(
            () -> nullEnv.bulkUpdate(superAdmin, Map.of("orders.order_id_prefix", "ZZZ")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CONFIG_KEY_IMMUTABLE");

    assertThat(PlatformConfigService.entityIdForKey("orders.delivery_fee")).isNotNull();
    when(cache.getAll())
        .thenReturn(
            Optional.of(
                List.of(row("orders.delivery_fee", "25", "integer", "INR", "orders", false))));
    when(store.listHistory("orders.delivery_fee")).thenReturn(List.of());
    assertThat(service.get(superAdmin, "/orders.delivery_fee")).containsEntry("value", 25);

    when(cache.getAll()).thenReturn(Optional.of(List.of()));
    assertThat(
            service.list(
                new MedmatePrincipal(
                    Ids.newId(), AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "f"),
                "  "))
        .isEmpty();
    assertThat(
            service.list(
                new MedmatePrincipal(
                    Ids.newId(), AuthRole.ADMIN_SUPPORT, null, TokenScope.FULL, "s"),
                null))
        .isEmpty();
    assertThat(
            service.list(
                new MedmatePrincipal(
                    Ids.newId(), AuthRole.ADMIN_COMPLIANCE, null, TokenScope.FULL, "c"),
                null))
        .isEmpty();
    assertThatThrownBy(() -> service.get(superAdmin, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void listAllDomainsAndHistoryParseFallbackAndDbMissRefill() {
    ConfigRow bad = row("orders.weird", "not-int", "integer", "INR", "orders", false);
    when(cache.getAll()).thenReturn(Optional.of(List.of(bad)));
    when(store.listHistory("orders.weird"))
        .thenReturn(
            List.of(
                new HistoryRow(
                    Ids.newId(), "orders.weird", null, "not-int", Ids.newId(), null, null, null)));
    Map<String, Object> one = service.get(superAdmin, "orders.weird");
    assertThat(one.get("value")).isEqualTo("not-int");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> history = (List<Map<String, Object>>) one.get("history");
    assertThat(history.get(0).get("old_value")).isNull();
    assertThat(history.get(0).get("changed_by")).isInstanceOf(Map.class);

    when(cache.getAll()).thenReturn(Optional.empty()).thenReturn(Optional.empty());
    when(store.listAll()).thenReturn(List.of(bad));
    Map<String, Object> listed = service.list(superAdmin, null);
    assertThat(listed).containsKey("orders");

    when(cache.getAll())
        .thenReturn(
            Optional.of(
                List.of(
                    row("orders.min_order_value", "49", "integer", "INR", "orders", false),
                    row("orders.delivery_fee", "25", "integer", "INR", "orders", false),
                    row("nodot", "1", "integer", "x", "orders", false),
                    row("orders.", "2", "integer", "x", "orders", false))));
    when(store.listHistory("orders.delivery_fee")).thenReturn(List.of());
    assertThat(service.get(superAdmin, "orders.delivery_fee")).containsEntry("value", 25);
    Map<String, Object> listedWithShort = service.list(superAdmin, "orders");
    @SuppressWarnings("unchecked")
    Map<String, Object> orders = (Map<String, Object>) listedWithShort.get("orders");
    assertThat(orders).containsKey("nodot").containsKey("orders.");
  }

  @Test
  void rateLimitAndEmptyKey() {
    when(cache.getAll()).thenReturn(Optional.of(List.of()));
    for (int i = 0; i < 30; i++) {
      service.list(superAdmin, null);
    }
    assertThatThrownBy(() -> service.list(superAdmin, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RATE_LIMITED");

    assertThatThrownBy(() -> service.get(superAdmin, "   "))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.get(superAdmin, "/"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThat(service.getTyped(null)).isEmpty();
    assertThat(service.getRaw("")).isEmpty();
  }

  private static ConfigRow row(
      String key, String value, String type, String unit, String domain, boolean immutable) {
    return new ConfigRow(key, value, type, unit, domain, immutable, "desc", null, NOW);
  }

  private static final class RecordingAudit implements AdminAuditAppendPort {
    final List<String> actions = new ArrayList<>();

    @Override
    public void append(
        String entityType,
        UUID actorId,
        String actorRole,
        UUID entityId,
        String action,
        Map<String, Object> before,
        Map<String, Object> after) {
      actions.add(action);
    }
  }
}
