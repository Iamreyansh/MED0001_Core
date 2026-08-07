package com.nammamedmate.catalogue.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.catalogue.application.CategoryService.CategoryListResult;
import com.nammamedmate.catalogue.application.port.out.ActiveMedicineCountPort;
import com.nammamedmate.catalogue.application.port.out.CategoryListCachePort;
import com.nammamedmate.catalogue.application.port.out.CategoryStore;
import com.nammamedmate.catalogue.application.port.out.CategoryStore.CategoryRow;
import com.nammamedmate.catalogue.application.port.out.CategoryStore.ReorderItem;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.InMemoryRateLimiter;
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

@ExtendWith(MockitoExtension.class)
class CategoryServiceBranchTest {

  private static final Instant NOW = Instant.parse("2026-08-08T00:00:00Z");

  @Mock private CategoryStore store;
  @Mock private ActiveMedicineCountPort medicineCount;
  @Mock private CategoryListCachePort cache;

  private CategoryService service;
  private final MedmatePrincipal ops =
      new MedmatePrincipal(
          UUID.randomUUID(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
  private final MedmatePrincipal superAdmin =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
  private final MedmatePrincipal finance =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    service =
        new CategoryService(
            store,
            medicineCount,
            cache,
            new InMemoryRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC)),
            Clock.fixed(NOW, ZoneOffset.UTC),
            new ObjectMapper());
  }

  @Test
  void listResult_nullMapsBecomeEmpty() {
    CategoryListResult result = new CategoryListResult(null, null);
    assertThat(result.data()).isEmpty();
    assertThat(result.meta()).isEmpty();
  }

  @Test
  void blankCacheValueFallsThrough() {
    when(cache.get()).thenReturn(Optional.of("   "));
    when(store.list(false, false)).thenReturn(List.of());
    assertThat(service.listPublic(null, false, false, "  ").meta()).containsEntry("total", 0);
  }

  @Test
  void validationNullNameBlankSlugBlankIconLongSlug() {
    assertThatThrownBy(
            () ->
                service.create(
                    ops, null, "ok", "https://cdn.nammamedmate.com/categories/ok.svg", true, 1))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.create(
                    ops, "Ab", " ", "https://cdn.nammamedmate.com/categories/ok.svg", true, 1))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_SLUG_FORMAT");
    assertThatThrownBy(
            () ->
                service.create(
                    ops,
                    "Ab",
                    "a".repeat(101),
                    "https://cdn.nammamedmate.com/categories/ok.svg",
                    true,
                    1))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_SLUG_FORMAT");
    assertThatThrownBy(() -> service.create(ops, "Ab", "ok", "  ", true, 1))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_ICON_URL");
  }

  @Test
  void reorderNullIdInItem() {
    assertThatThrownBy(() -> service.reorder(ops, List.of(new ReorderItem(null, 1))))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_CATEGORY_ID");
  }

  @Test
  void updateSoftDeletedNotFound() {
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
    assertThatThrownBy(() -> service.update(ops, id, "B", null, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CATEGORY_NOT_FOUND");
  }

  @Test
  void includeHiddenSuperAdminAndFinanceMutateForbidden() {
    when(store.list(true, false)).thenReturn(List.of());
    service.listPublic(superAdmin, true, false, "1.1.1.1");
    when(cache.get()).thenReturn(Optional.empty());
    when(store.list(false, false)).thenReturn(List.of());
    service.listPublic(null, true, false, "1.1.1.1");
    assertThatThrownBy(
            () ->
                service.create(
                    finance, "Ab", "ab", "https://cdn.nammamedmate.com/categories/ab.svg", true, 1))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    when(store.existsBySlug(anyString())).thenReturn(false);
    when(store.existsByName(anyString())).thenReturn(false);
    when(store.nextDisplayOrder()).thenReturn(1);
    assertThat(
            service.create(
                superAdmin,
                "Super Cat",
                "super-cat",
                "https://cdn.nammamedmate.com/categories/super-cat.svg",
                true,
                null))
        .containsEntry("display_order", 1);
  }

  @Test
  void cachedPayloadWithoutCategoriesKey() {
    when(cache.get()).thenReturn(Optional.of("{\"total\":0,\"cached_at\":\"t\"}"));
    CategoryListResult result = service.listPublic(null, false, false, "ip");
    assertThat(result.data()).containsKey("categories");
    assertThat(result.meta()).containsEntry("total", 0);
  }
}
