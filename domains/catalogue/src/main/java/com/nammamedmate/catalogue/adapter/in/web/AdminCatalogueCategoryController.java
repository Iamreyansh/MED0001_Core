package com.nammamedmate.catalogue.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.catalogue.application.CategoryService;
import com.nammamedmate.catalogue.application.port.out.CategoryStore.ReorderItem;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/catalogue/categories")
@Tag(name = "Admin catalogue categories")
public class AdminCatalogueCategoryController {

  private final CategoryService service;

  public AdminCatalogueCategoryController(CategoryService service) {
    this.service = service;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Create medicine category")
  public ApiResponse<Map<String, Object>> create(
      @AuthenticationPrincipal MedmatePrincipal principal, @RequestBody CreateRequest body) {
    CreateRequest req = body == null ? new CreateRequest(null, null, null, null, null) : body;
    return ApiResponse.ok(
        service.create(
            principal, req.name(), req.slug(), req.iconUrl(), req.isVisible(), req.displayOrder()));
  }

  @PatchMapping("/reorder")
  @Operation(summary = "Bulk reorder categories atomically")
  public ApiResponse<Map<String, Object>> reorder(
      @AuthenticationPrincipal MedmatePrincipal principal, @RequestBody ReorderRequest body) {
    List<ReorderItem> items =
        body == null || body.items() == null
            ? List.of()
            : body.items().stream()
                .map(i -> new ReorderItem(i.id(), i.displayOrder() == null ? 0 : i.displayOrder()))
                .toList();
    return ApiResponse.ok(service.reorder(principal, items));
  }

  @PatchMapping("/{id}")
  @Operation(summary = "Update medicine category")
  public ApiResponse<Map<String, Object>> update(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestBody UpdateRequest body) {
    UpdateRequest req = body == null ? new UpdateRequest(null, null, null, null) : body;
    return ApiResponse.ok(
        service.update(
            principal, id, req.name(), req.iconUrl(), req.isVisible(), req.displayOrder()));
  }

  @DeleteMapping("/{id}")
  @Operation(summary = "Soft-delete medicine category (admin_super)")
  public ApiResponse<Map<String, Object>> delete(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("id") UUID id) {
    return ApiResponse.ok(service.delete(principal, id));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record CreateRequest(
      String name, String slug, String iconUrl, Boolean isVisible, Integer displayOrder) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record UpdateRequest(
      String name, String iconUrl, Boolean isVisible, Integer displayOrder) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record ReorderRequest(List<ReorderItemRequest> items) {
    public ReorderRequest {
      items = items == null ? null : List.copyOf(items);
    }
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record ReorderItemRequest(UUID id, Integer displayOrder) {}
}
