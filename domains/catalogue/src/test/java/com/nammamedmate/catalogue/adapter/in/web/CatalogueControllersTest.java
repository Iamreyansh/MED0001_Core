package com.nammamedmate.catalogue.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.catalogue.application.CategoryService;
import com.nammamedmate.catalogue.application.CategoryService.CategoryListResult;
import com.nammamedmate.catalogue.application.ScheduleRulesService;
import com.nammamedmate.catalogue.application.port.out.CategoryStore.ReorderItem;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CatalogueControllersTest {

  @Mock private CategoryService categoryService;
  @Mock private ScheduleRulesService scheduleRulesService;

  private CatalogueCategoryController publicController;
  private AdminCatalogueCategoryController adminController;
  private AdminScheduleRulesController scheduleController;

  private final MedmatePrincipal admin =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    publicController = new CatalogueCategoryController(categoryService);
    adminController = new AdminCatalogueCategoryController(categoryService);
    scheduleController = new AdminScheduleRulesController(scheduleRulesService);
  }

  @Test
  void publicList_wrapsEnvelopeWithMeta() {
    when(categoryService.listPublic(isNull(), eq(false), eq(false), eq("10.0.0.1")))
        .thenReturn(
            new CategoryListResult(
                Map.of("categories", List.of()), Map.of("total", 0, "cached_at", "t")));
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getRemoteAddr()).thenReturn("10.0.0.1");

    Map<String, Object> body = publicController.list(null, false, false, request);

    assertThat(body).containsEntry("success", true);
    assertThat(body.get("meta")).isEqualTo(Map.of("total", 0, "cached_at", "t"));
  }

  @Test
  void publicList_nullRequestIp() {
    when(categoryService.listPublic(any(), anyBoolean(), anyBoolean(), isNull()))
        .thenReturn(new CategoryListResult(Map.of("categories", List.of()), Map.of("total", 0)));

    publicController.list(admin, true, false, null);
    verify(categoryService).listPublic(admin, true, false, null);

    publicController.list(admin, false, true, null);
    verify(categoryService).listPublic(admin, false, true, null);
  }

  @Test
  void publicList_blankRemoteAddr() {
    when(categoryService.listPublic(any(), anyBoolean(), anyBoolean(), isNull()))
        .thenReturn(new CategoryListResult(Map.of("categories", List.of()), Map.of("total", 0)));
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getRemoteAddr()).thenReturn("  ");
    publicController.list(null, false, false, request);
    verify(categoryService).listPublic(null, false, false, null);

    HttpServletRequest nullAddr = mock(HttpServletRequest.class);
    when(nullAddr.getRemoteAddr()).thenReturn(null);
    publicController.list(null, false, false, nullAddr);
  }

  @Test
  void adminCreate_update_delete_reorder() {
    UUID id = UUID.randomUUID();
    when(categoryService.create(any(), any(), any(), any(), any(), any()))
        .thenReturn(Map.of("category_id", id.toString()));
    when(categoryService.update(any(), any(), any(), any(), any(), any()))
        .thenReturn(Map.of("updated_fields", List.of()));
    when(categoryService.delete(any(), any())).thenReturn(Map.of("deleted", true));
    when(categoryService.reorder(any(), any())).thenReturn(Map.of("reordered_count", 1));

    assertThat(
            adminController
                .create(
                    admin,
                    new AdminCatalogueCategoryController.CreateRequest(
                        "N", "n", "https://cdn.nammamedmate.com/categories/n.svg", true, 1))
                .data())
        .containsEntry("category_id", id.toString());
    adminController.create(admin, null);

    adminController.update(
        admin, id, new AdminCatalogueCategoryController.UpdateRequest("N2", null, false, 2));
    adminController.update(admin, id, null);

    assertThat(adminController.delete(admin, id).data()).containsEntry("deleted", true);

    adminController.reorder(
        admin,
        new AdminCatalogueCategoryController.ReorderRequest(
            List.of(new AdminCatalogueCategoryController.ReorderItemRequest(id, 1))));
    adminController.reorder(admin, null);
    adminController.reorder(admin, new AdminCatalogueCategoryController.ReorderRequest(null));
    adminController.reorder(
        admin,
        new AdminCatalogueCategoryController.ReorderRequest(
            List.of(new AdminCatalogueCategoryController.ReorderItemRequest(id, null))));

    ArgumentCaptor<List<ReorderItem>> captor = ArgumentCaptor.forClass(List.class);
    verify(categoryService, org.mockito.Mockito.atLeastOnce()).reorder(eq(admin), captor.capture());
    assertThat(captor.getValue()).isNotNull();
  }

  @Test
  void scheduleRules_delegates() {
    when(scheduleRulesService.get(admin)).thenReturn(Map.of("schedules", List.of()));
    ApiResponse<Map<String, Object>> response = scheduleController.get(admin);
    assertThat(response.success()).isTrue();
    verify(scheduleRulesService).get(admin);
  }
}
