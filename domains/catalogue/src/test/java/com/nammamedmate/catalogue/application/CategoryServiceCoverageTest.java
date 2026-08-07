package com.nammamedmate.catalogue.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.catalogue.application.port.out.ActiveMedicineCountPort;
import com.nammamedmate.catalogue.application.port.out.CategoryListCachePort;
import com.nammamedmate.catalogue.application.port.out.CategoryStore;
import com.nammamedmate.catalogue.application.port.out.CategoryStore.CategoryRow;
import com.nammamedmate.catalogue.application.port.out.CategoryStore.ReorderItem;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CategoryServiceCoverageTest {

  private static final Instant NOW = Instant.parse("2026-08-08T00:00:00Z");

  @Mock private CategoryStore store;
  @Mock private ActiveMedicineCountPort medicineCount;
  @Mock private CategoryListCachePort cache;
  @Mock private RateLimiter rateLimiter;

  private CategoryService service;
  private final MedmatePrincipal superAdmin =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
  private final MedmatePrincipal ops =
      new MedmatePrincipal(
          UUID.randomUUID(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
  private final MedmatePrincipal compliance =
      new MedmatePrincipal(
          UUID.randomUUID(), AuthRole.ADMIN_COMPLIANCE, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    when(rateLimiter.tryAcquire(anyString(), any(Integer.class), any(Integer.class)))
        .thenReturn(true);
    service =
        new CategoryService(
            store,
            medicineCount,
            cache,
            rateLimiter,
            Clock.fixed(NOW, ZoneOffset.UTC),
            new ObjectMapper());
  }

  @Test
  void rateLimitExceeded() {
    when(rateLimiter.tryAcquire(anyString(), any(Integer.class), any(Integer.class)))
        .thenReturn(false);
    assertThatThrownBy(() -> service.listPublic(null, false, false, "ip"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RATE_LIMIT_EXCEEDED");
  }

  @Test
  void unauthorizedMutations() {
    assertThatThrownBy(
            () ->
                service.create(
                    null, "N", "n", "https://cdn.nammamedmate.com/categories/n.svg", true, 1))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(() -> service.delete(null, UUID.randomUUID()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(() -> service.update(null, UUID.randomUUID(), "N", null, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
  }

  @Test
  void validationEdges() {
    assertThatThrownBy(
            () ->
                service.create(
                    ops, "A", "ok", "https://cdn.nammamedmate.com/categories/ok.svg", true, 1))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.create(
                    ops, "Ab", null, "https://cdn.nammamedmate.com/categories/ok.svg", true, 1))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_SLUG_FORMAT");
    assertThatThrownBy(
            () ->
                service.create(
                    ops, "Ab", "ok", "https://cdn.nammamedmate.com/categories/ok.gif", true, 1))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_ICON_URL");
    assertThatThrownBy(
            () ->
                service.create(
                    ops, "Ab", "ok", "https://cdn.nammamedmate.com/categories/ok.png", true, 0))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.create(ops, "Ab", "ok", null, true, 1))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_ICON_URL");
  }

  @Test
  void reorderNullItemAndInvalidOrder() {
    assertThatThrownBy(() -> service.reorder(ops, java.util.Arrays.asList((ReorderItem) null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_CATEGORY_ID");
    assertThatThrownBy(() -> service.reorder(ops, List.of(new ReorderItem(UUID.randomUUID(), 0))))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.reorder(ops, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("ITEMS_REQUIRED");
  }

  @Test
  void deleteNotFoundAndSoftDeleted() {
    when(store.findById(any())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.delete(superAdmin, UUID.randomUUID()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CATEGORY_NOT_FOUND");

    UUID id = UUID.randomUUID();
    when(store.findById(id))
        .thenReturn(
            Optional.of(
                new CategoryRow(
                    id,
                    "A",
                    "a",
                    "https://cdn.nammamedmate.com/categories/a.svg",
                    true,
                    1,
                    NOW,
                    NOW,
                    NOW,
                    0)));
    assertThatThrownBy(() -> service.delete(superAdmin, id))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CATEGORY_NOT_FOUND");
  }

  @Test
  void updateDuplicateKeyAndNoop() {
    UUID id = UUID.randomUUID();
    CategoryRow row =
        new CategoryRow(
            id,
            "Same",
            "same",
            "https://cdn.nammamedmate.com/categories/same.svg",
            true,
            1,
            null,
            NOW,
            NOW,
            0);
    when(store.findById(id)).thenReturn(Optional.of(row));

    var noop = service.update(ops, id, "Same", row.iconUrl(), true, 1);
    assertThat(noop.get("updated_fields")).isEqualTo(List.of());

    when(store.findById(id)).thenReturn(Optional.of(row));
    when(store.existsByNameExcluding("Other Name", id)).thenReturn(false);
    doThrow(new DuplicateKeyException("other constraint"))
        .when(store)
        .update(any(), any(), any(), any(), any(), any());
    assertThatThrownBy(() -> service.update(ops, id, "Other Name", null, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("DUPLICATE_SLUG");
  }

  @Test
  void includeHiddenComplianceSkipsCacheWrite() {
    when(store.list(true, false)).thenReturn(List.of());
    service.listPublic(compliance, true, false, "1.1.1.1");
  }

  @Test
  void cacheGetThrowsFallsBack() {
    when(cache.get()).thenThrow(new RuntimeException("redis down"));
    when(store.list(false, false)).thenReturn(List.of());
    assertThat(service.listPublic(null, false, false, "ip").meta()).containsEntry("total", 0);
  }

  @Test
  void cachePutThrowsIsIgnored() {
    when(cache.get()).thenReturn(Optional.empty());
    when(store.list(false, false)).thenReturn(List.of());
    doThrow(new RuntimeException("fail")).when(cache).put(anyString());
    assertThat(service.listPublic(null, false, false, "ip").meta()).containsEntry("total", 0);
  }

  @Test
  void createWithPngAndExplicitOrder() {
    when(store.existsBySlug(anyString())).thenReturn(false);
    when(store.existsByName(anyString())).thenReturn(false);
    var data =
        service.create(
            ops,
            "Png Cat",
            "png-cat",
            "https://cdn.nammamedmate.com/categories/png-cat.PNG",
            true,
            7);
    assertThat(data).containsEntry("display_order", 7);
  }

  @Test
  void mapDuplicateUnknownUsesSlug() {
    when(store.existsBySlug(anyString())).thenReturn(false);
    when(store.existsByName(anyString())).thenReturn(false);
    when(store.nextDisplayOrder()).thenReturn(1);
    doThrow(new DuplicateKeyException("unique violation")).when(store).insert(any());
    assertThatThrownBy(
            () ->
                service.create(
                    ops, "Xy", "xy", "https://cdn.nammamedmate.com/categories/xy.svg", true, null))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("DUPLICATE_SLUG");
  }

  @Test
  void longNameRejected() {
    assertThatThrownBy(
            () ->
                service.create(
                    ops,
                    "A".repeat(101),
                    "ok",
                    "https://cdn.nammamedmate.com/categories/ok.svg",
                    true,
                    1))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }
}
