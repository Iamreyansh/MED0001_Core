package com.nammamedmate.catalogue.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-08T00:00:00Z");

  @Mock private CategoryStore store;
  @Mock private ActiveMedicineCountPort medicineCount;
  @Mock private CategoryListCachePort cache;

  private InMemoryRateLimiter rateLimiter;
  private CategoryService service;

  private final MedmatePrincipal superAdmin =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
  private final MedmatePrincipal ops =
      new MedmatePrincipal(
          UUID.randomUUID(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
  private final MedmatePrincipal customer =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    rateLimiter = new InMemoryRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC));
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
  void listPublic_returnsVisibleOnlySorted_withMedicineCount() {
    when(cache.get()).thenReturn(Optional.empty());
    UUID id = UUID.randomUUID();
    when(store.list(false, false))
        .thenReturn(
            List.of(
                row(id, "Antibiotics", "antibiotics", true, 1, null),
                row(UUID.randomUUID(), "Pain Relief", "pain-relief", true, 2, null)));

    CategoryListResult result = service.listPublic(null, false, false, "1.2.3.4");

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> categories =
        (List<Map<String, Object>>) result.data().get("categories");
    assertThat(categories).hasSize(2);
    assertThat(categories.get(0)).containsEntry("medicine_count", 0);
    assertThat(result.meta()).containsEntry("total", 2).containsEntry("cached_at", NOW.toString());
    verify(cache).put(anyString());
  }

  @Test
  void listPublic_servesFromCache() {
    when(cache.get())
        .thenReturn(
            Optional.of(
                "{\"categories\":[{\"category_id\":\"x\",\"name\":\"A\",\"slug\":\"a\","
                    + "\"icon_url\":\"https://cdn.nammamedmate.com/categories/a.svg\","
                    + "\"is_visible\":true,\"display_order\":1,\"medicine_count\":0}],"
                    + "\"total\":1,\"cached_at\":\"2026-08-08T00:00:00Z\"}"));

    CategoryListResult result = service.listPublic(null, false, false, "1.2.3.4");

    assertThat(result.meta()).containsEntry("total", 1);
    verify(store, never()).list(anyBoolean(), anyBoolean());
  }

  @Test
  void listPublic_includeHiddenRequiresAdmin() {
    when(cache.get()).thenReturn(Optional.empty());
    when(store.list(false, false)).thenReturn(List.of());

    service.listPublic(customer, true, false, "1.1.1.1");

    verify(store).list(false, false);
  }

  @Test
  void listPublic_includeHiddenForAdminSkipsCache() {
    when(store.list(true, false))
        .thenReturn(List.of(row(UUID.randomUUID(), "Hidden", "hidden", false, 9, null)));

    CategoryListResult result = service.listPublic(ops, true, false, "1.1.1.1");

    assertThat(result.meta()).containsEntry("total", 1);
    verify(cache, never()).get();
    verify(cache, never()).put(anyString());
  }

  @Test
  void listPublic_includeDeletedForAdminReturnsSoftDeleted() {
    UUID id = UUID.randomUUID();
    when(store.list(false, true)).thenReturn(List.of(row(id, "Deleted", "deleted", true, 1, NOW)));

    CategoryListResult result = service.listPublic(ops, false, true, "1.1.1.1");

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> categories =
        (List<Map<String, Object>>) result.data().get("categories");
    assertThat(categories).hasSize(1);
    assertThat(categories.getFirst())
        .containsEntry("category_id", id.toString())
        .containsEntry("is_deleted", true)
        .containsEntry("deleted_at", NOW.toString());
    verify(store).list(false, true);
    verify(cache, never()).get();
  }

  @Test
  void listPublic_includeDeletedIgnoredForNonAdmin() {
    when(cache.get()).thenReturn(Optional.empty());
    when(store.list(false, false)).thenReturn(List.of());

    service.listPublic(customer, false, true, "1.1.1.1");

    verify(store).list(false, false);
  }

  @Test
  void create_duplicateSlug_returns409() {
    when(store.existsBySlug("pain-relief")).thenReturn(true);

    assertThatThrownBy(
            () ->
                service.create(
                    ops,
                    "Pain Relief 2",
                    "pain-relief",
                    "https://cdn.nammamedmate.com/categories/pain-relief.svg",
                    true,
                    null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("DUPLICATE_SLUG");
  }

  @Test
  void create_invalidSlugAndIcon() {
    assertThatThrownBy(
            () -> service.create(ops, "Pain", "Pain Relief", "https://cdn.x/a.svg", true, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_SLUG_FORMAT");
    assertThatThrownBy(() -> service.create(ops, "Pain", "pain", "http://cdn.x/a.svg", true, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_ICON_URL");
  }

  @Test
  void create_success_invalidatesCache() {
    when(store.existsBySlug(anyString())).thenReturn(false);
    when(store.existsByName(anyString())).thenReturn(false);
    when(store.nextDisplayOrder()).thenReturn(49);

    Map<String, Object> data =
        service.create(
            ops,
            "Diabetic Care Extra",
            "diabetic-care-extra",
            "https://cdn.nammamedmate.com/categories/diabetic-care-extra.svg",
            null,
            null);

    assertThat(data).containsEntry("display_order", 49).containsEntry("medicine_count", 0);
    verify(cache).invalidate();
  }

  @Test
  void create_duplicateKeyMapsName() {
    when(store.existsBySlug(anyString())).thenReturn(false);
    when(store.existsByName(anyString())).thenReturn(false);
    doThrow(new DuplicateKeyException("uq_medicine_category_name")).when(store).insert(any());

    assertThatThrownBy(
            () ->
                service.create(
                    ops,
                    "Antibiotics",
                    "antibiotics-2",
                    "https://cdn.nammamedmate.com/categories/a.svg",
                    true,
                    1))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("DUPLICATE_NAME");
  }

  @Test
  void update_hidesFromPublicList_andInvalidatesCache() {
    UUID id = UUID.randomUUID();
    when(store.findById(id))
        .thenReturn(Optional.of(row(id, "Antibiotics", "antibiotics", true, 1, null)));

    Map<String, Object> data = service.update(ops, id, null, null, false, null);

    assertThat(data.get("updated_fields")).isEqualTo(List.of("is_visible"));
    verify(store).update(eq(id), eq(null), eq(null), eq(false), eq(null), eq(NOW));
    verify(cache).invalidate();
  }

  @Test
  void update_notFound() {
    when(store.findById(any())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.update(ops, UUID.randomUUID(), "X", null, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CATEGORY_NOT_FOUND");
  }

  @Test
  void delete_withActiveMedicines_returns409() {
    UUID id = UUID.randomUUID();
    when(store.findById(id))
        .thenReturn(Optional.of(row(id, "Antibiotics", "antibiotics", true, 1, null)));
    when(medicineCount.countActiveByCategoryId(id)).thenReturn(10);

    assertThatThrownBy(() -> service.delete(superAdmin, id))
        .isInstanceOf(AppException.class)
        .satisfies(
            ex -> {
              AppException app = (AppException) ex;
              assertThat(app.code()).isEqualTo("CATEGORY_HAS_ACTIVE_MEDICINES");
              assertThat(app.httpStatus()).isEqualTo(409);
            });
    verify(store, never()).softDelete(any(), any());
  }

  @Test
  void delete_withZeroMedicines_softDeletes() {
    UUID id = UUID.randomUUID();
    when(store.findById(id))
        .thenReturn(Optional.of(row(id, "Antibiotics", "antibiotics", true, 1, null)));
    when(medicineCount.countActiveByCategoryId(id)).thenReturn(0);

    Map<String, Object> data = service.delete(superAdmin, id);

    assertThat(data).containsEntry("deleted", true).containsEntry("deleted_at", NOW.toString());
    verify(store).softDelete(id, NOW);
    verify(cache).invalidate();
  }

  @Test
  void delete_opsForbidden() {
    assertThatThrownBy(() -> service.delete(ops, UUID.randomUUID()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void reorder_atomicSuccess() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    when(store.countExistingIds(List.of(a, b))).thenReturn(2);

    Map<String, Object> data =
        service.reorder(ops, List.of(new ReorderItem(a, 1), new ReorderItem(b, 2)));

    assertThat(data).containsEntry("reordered_count", 2);
    verify(store).reorder(anyList(), eq(NOW));
    verify(cache).invalidate();
  }

  @Test
  void reorder_invalidId_rollsBackConceptually() {
    UUID a = UUID.randomUUID();
    UUID missing = UUID.randomUUID();
    when(store.countExistingIds(List.of(a, missing))).thenReturn(1);

    assertThatThrownBy(
            () -> service.reorder(ops, List.of(new ReorderItem(a, 1), new ReorderItem(missing, 2))))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_CATEGORY_ID");
    verify(store, never()).reorder(anyList(), any());
  }

  @Test
  void reorder_duplicateDisplayOrder() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    assertThatThrownBy(
            () -> service.reorder(ops, List.of(new ReorderItem(a, 1), new ReorderItem(b, 1))))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("DUPLICATE_DISPLAY_ORDER");
  }

  @Test
  void reorder_emptyItems() {
    assertThatThrownBy(() -> service.reorder(ops, List.of()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("ITEMS_REQUIRED");
  }

  @Test
  void create_forbiddenForCustomer() {
    assertThatThrownBy(
            () ->
                service.create(
                    customer, "X", "x", "https://cdn.nammamedmate.com/categories/x.svg", true, 1))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void listPublic_corruptCacheFallsBackToDb() {
    when(cache.get()).thenReturn(Optional.of("{not-json"));
    when(store.list(false, false)).thenReturn(List.of());

    CategoryListResult result = service.listPublic(null, false, false, null);
    assertThat(result.meta()).containsEntry("total", 0);
  }

  @Test
  void create_duplicateKeyMapsSlug() {
    when(store.existsBySlug(anyString())).thenReturn(false);
    when(store.existsByName(anyString())).thenReturn(false);
    doThrow(new DuplicateKeyException("uq_medicine_category_slug")).when(store).insert(any());

    assertThatThrownBy(
            () ->
                service.create(
                    ops,
                    "New Name",
                    "antibiotics",
                    "https://cdn.nammamedmate.com/categories/a.svg",
                    true,
                    1))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("DUPLICATE_SLUG");
  }

  @Test
  void update_nameAndIconAndOrder() {
    UUID id = UUID.randomUUID();
    when(store.findById(id)).thenReturn(Optional.of(row(id, "Old", "old", true, 1, null)));
    when(store.existsByNameExcluding("New Name", id)).thenReturn(false);

    Map<String, Object> data =
        service.update(
            ops, id, "New Name", "https://cdn.nammamedmate.com/categories/new.png", true, 5);

    @SuppressWarnings("unchecked")
    List<String> fields = (List<String>) data.get("updated_fields");
    assertThat(fields).containsExactlyInAnyOrder("name", "icon_url", "display_order");
  }

  @Test
  void update_duplicateName() {
    UUID id = UUID.randomUUID();
    when(store.findById(id)).thenReturn(Optional.of(row(id, "Old", "old", true, 1, null)));
    when(store.existsByNameExcluding("Taken", id)).thenReturn(true);

    assertThatThrownBy(() -> service.update(ops, id, "Taken", null, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("DUPLICATE_NAME");
  }

  @Test
  void create_rejectsBlankNameAndDuplicateNamePrecheck() {
    assertThatThrownBy(
            () ->
                service.create(
                    ops, " ", "ok", "https://cdn.nammamedmate.com/categories/ok.svg", true, 1))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    when(store.existsBySlug("ok")).thenReturn(false);
    when(store.existsByName("Taken")).thenReturn(true);
    assertThatThrownBy(
            () ->
                service.create(
                    ops, "Taken", "ok", "https://cdn.nammamedmate.com/categories/ok.svg", true, 1))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("DUPLICATE_NAME");
  }

  @Test
  void create_insertCapturesRow() {
    when(store.existsBySlug(anyString())).thenReturn(false);
    when(store.existsByName(anyString())).thenReturn(false);
    when(store.nextDisplayOrder()).thenReturn(3);

    service.create(
        ops, "Cat", "cat", "https://cdn.nammamedmate.com/categories/cat.svg", false, null);

    ArgumentCaptor<CategoryRow> captor = ArgumentCaptor.forClass(CategoryRow.class);
    verify(store).insert(captor.capture());
    assertThat(captor.getValue().visible()).isFalse();
    assertThat(captor.getValue().displayOrder()).isEqualTo(3);
  }

  private static CategoryRow row(
      UUID id, String name, String slug, boolean visible, int order, Instant deletedAt) {
    return new CategoryRow(
        id,
        name,
        slug,
        "https://cdn.nammamedmate.com/categories/" + slug + ".svg",
        visible,
        order,
        deletedAt,
        NOW,
        NOW,
        0);
  }
}
