package com.nammamedmate.marketing.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.marketing.application.BannerService;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/banners")
@Tag(name = "Admin banners")
public class AdminBannerController {

  private final BannerService banners;

  public AdminBannerController(BannerService banners) {
    this.banners = banners;
  }

  @GetMapping
  @Operation(summary = "List banners")
  public ApiResponse<Map<String, Object>> list(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(required = false) String placement,
      @RequestParam(name = "is_live", required = false) Boolean isLive,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer limit) {
    BannerService.PagedResult result = banners.listAdmin(principal, placement, isLive, page, limit);
    return ApiResponse.ok(result.data(), result.meta());
  }

  @PostMapping
  @Operation(summary = "Create banner")
  public ResponseEntity<ApiResponse<Map<String, Object>>> create(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) CreateBannerRequest body) {
    CreateBannerRequest req =
        body == null
            ? new CreateBannerRequest(
                null, null, null, null, null, null, null, null, null, null, null)
            : body;
    Map<String, Object> data =
        banners.create(
            principal,
            new BannerService.CreateCommand(
                req.headline(),
                req.subText(),
                req.imageUrl(),
                req.placement(),
                req.linkType(),
                req.linkValue(),
                req.themeColor(),
                req.isLive(),
                req.validFrom(),
                req.validUntil(),
                req.priority()));
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(data));
  }

  @PatchMapping("/reorder")
  @Operation(summary = "Bulk reorder banners within one placement")
  public ApiResponse<Map<String, Object>> reorder(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) ReorderRequest body) {
    List<BannerService.ReorderItem> items = new ArrayList<>();
    if (body != null && body.items() != null) {
      for (ReorderItemRequest it : body.items()) {
        items.add(
            new BannerService.ReorderItem(
                it == null ? null : it.id(), it == null ? null : it.priority()));
      }
    }
    return ApiResponse.ok(banners.reorder(principal, items));
  }

  @PatchMapping("/{id}")
  @Operation(summary = "Update banner")
  public ApiResponse<Map<String, Object>> patch(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestBody(required = false) PatchBannerRequest body) {
    PatchBannerRequest req =
        body == null
            ? new PatchBannerRequest(
                null, null, null, null, null, null, null, null, null, null, null)
            : body;
    return ApiResponse.ok(
        banners.patch(
            principal,
            id,
            new BannerService.PatchCommand(
                req.headline(),
                req.subText(),
                req.imageUrl(),
                req.placement(),
                req.linkType(),
                req.linkValue(),
                req.themeColor(),
                req.isLive(),
                req.validFrom(),
                req.validUntil(),
                req.priority())));
  }

  @PatchMapping("/{id}/toggle")
  @Operation(summary = "Toggle banner live/offline")
  public ApiResponse<Map<String, Object>> toggle(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("id") UUID id) {
    return ApiResponse.ok(banners.toggle(principal, id));
  }

  @DeleteMapping("/{id}")
  @Operation(summary = "Delete banner (super only)")
  public ApiResponse<Map<String, Object>> delete(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("id") UUID id) {
    return ApiResponse.ok(banners.delete(principal, id));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record CreateBannerRequest(
      String headline,
      String subText,
      String imageUrl,
      String placement,
      String linkType,
      String linkValue,
      String themeColor,
      Boolean isLive,
      Instant validFrom,
      Instant validUntil,
      Integer priority) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record PatchBannerRequest(
      String headline,
      String subText,
      String imageUrl,
      String placement,
      String linkType,
      String linkValue,
      String themeColor,
      Boolean isLive,
      Instant validFrom,
      Instant validUntil,
      Integer priority) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record ReorderRequest(List<ReorderItemRequest> items) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record ReorderItemRequest(UUID id, Integer priority) {}
}
