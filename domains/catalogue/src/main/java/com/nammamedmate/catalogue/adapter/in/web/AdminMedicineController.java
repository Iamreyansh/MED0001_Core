package com.nammamedmate.catalogue.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.catalogue.application.MedicineService;
import com.nammamedmate.catalogue.application.MedicineService.CreateCommand;
import com.nammamedmate.catalogue.application.MedicineService.PageResult;
import com.nammamedmate.catalogue.application.MedicineService.UpdateCommand;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/catalogue")
@Tag(name = "Admin catalogue medicines")
public class AdminMedicineController {

  private final MedicineService service;

  public AdminMedicineController(MedicineService service) {
    this.service = service;
  }

  @GetMapping
  @Operation(summary = "List master medicines")
  public ApiResponse<Map<String, Object>> list(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(value = "category_id", required = false) UUID categoryId,
      @RequestParam(value = "schedule", required = false) String schedule,
      @RequestParam(value = "gst_pct", required = false) Integer gstPct,
      @RequestParam(value = "is_rx_only", required = false) Boolean isRxOnly,
      @RequestParam(value = "is_banned", required = false) Boolean isBanned,
      @RequestParam(value = "search", required = false) String search,
      @RequestParam(value = "sort", required = false) String sort,
      @RequestParam(value = "order", required = false) String order,
      @RequestParam(value = "page", required = false) Integer page,
      @RequestParam(value = "limit", required = false) Integer limit) {
    PageResult result =
        service.list(
            principal,
            categoryId,
            schedule,
            gstPct,
            isRxOnly,
            isBanned,
            search,
            sort,
            order,
            page,
            limit);
    return ApiResponse.ok(result.data(), result.meta());
  }

  @GetMapping("/summary")
  @Operation(summary = "Catalogue summary KPIs")
  public ApiResponse<Map<String, Object>> summary(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(service.summary(principal));
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Create master medicine")
  public ApiResponse<Map<String, Object>> create(
      @AuthenticationPrincipal MedmatePrincipal principal, @RequestBody CreateRequest body) {
    CreateRequest req =
        body == null
            ? new CreateRequest(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null)
            : body;
    return ApiResponse.ok(
        service.create(
            principal,
            new CreateCommand(
                req.name(),
                req.saltComposition(),
                req.manufacturer(),
                req.categoryId(),
                req.form(),
                req.packSize(),
                req.packUnit(),
                req.schedule(),
                req.hsnCode(),
                req.gstPct(),
                req.mrp(),
                req.isRxOnly(),
                req.description(),
                req.substitutes(),
                req.monthlyDemand())));
  }

  @GetMapping("/{id}")
  @Operation(summary = "Get medicine detail")
  public ApiResponse<Map<String, Object>> get(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("id") UUID id) {
    return ApiResponse.ok(service.get(principal, id));
  }

  @PatchMapping("/{id}")
  @Operation(summary = "Update medicine details")
  public ApiResponse<Map<String, Object>> update(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestBody UpdateRequest body) {
    UpdateRequest req =
        body == null
            ? new UpdateRequest(null, null, null, null, null, null, null, null, null)
            : body;
    return ApiResponse.ok(
        service.update(
            principal,
            id,
            new UpdateCommand(
                req.name(),
                req.description(),
                req.categoryId(),
                req.schedule(),
                req.gstPct(),
                req.mrp(),
                req.isRxOnly(),
                req.substitutes(),
                req.monthlyDemand())));
  }

  @PostMapping("/{id}/ban")
  @Operation(summary = "Ban medicine platform-wide")
  public ApiResponse<Map<String, Object>> ban(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestBody BanRequest body) {
    BanRequest req = body == null ? new BanRequest(null) : body;
    return ApiResponse.ok(service.ban(principal, id, req.reason()));
  }

  @PostMapping("/{id}/unban")
  @Operation(summary = "Un-ban medicine")
  public ApiResponse<Map<String, Object>> unban(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestBody BanRequest body) {
    BanRequest req = body == null ? new BanRequest(null) : body;
    return ApiResponse.ok(service.unban(principal, id, req.reason()));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record CreateRequest(
      String name,
      String saltComposition,
      String manufacturer,
      UUID categoryId,
      String form,
      Object packSize,
      String packUnit,
      String schedule,
      String hsnCode,
      Integer gstPct,
      Object mrp,
      Boolean isRxOnly,
      String description,
      List<UUID> substitutes,
      Object monthlyDemand) {
    public CreateRequest {
      substitutes = substitutes == null ? null : List.copyOf(substitutes);
    }
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record UpdateRequest(
      String name,
      String description,
      UUID categoryId,
      String schedule,
      Integer gstPct,
      Object mrp,
      Boolean isRxOnly,
      List<UUID> substitutes,
      Object monthlyDemand) {
    public UpdateRequest {
      substitutes = substitutes == null ? null : List.copyOf(substitutes);
    }
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record BanRequest(String reason) {}
}
