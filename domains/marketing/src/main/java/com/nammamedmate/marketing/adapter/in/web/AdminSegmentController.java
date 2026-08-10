package com.nammamedmate.marketing.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.marketing.application.SegmentService;
import com.nammamedmate.marketing.domain.SegmentCriterion;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/segments")
@Tag(name = "Admin customer segments")
public class AdminSegmentController {

  private final SegmentService segments;

  public AdminSegmentController(SegmentService segments) {
    this.segments = segments;
  }

  @GetMapping
  @Operation(summary = "List segments")
  public ApiResponse<Map<String, Object>> list(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(name = "segment_type", required = false) String segmentType,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer limit) {
    SegmentService.PagedResult result = segments.list(principal, segmentType, page, limit);
    return ApiResponse.ok(result.data(), result.meta());
  }

  @PostMapping
  @Operation(summary = "Create custom segment")
  public ResponseEntity<ApiResponse<Map<String, Object>>> create(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) CreateSegmentRequest body) {
    CreateSegmentRequest req = body == null ? new CreateSegmentRequest(null, null, null) : body;
    Map<String, Object> data =
        segments.create(principal, req.name(), req.description(), req.criteria());
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(data));
  }

  @GetMapping("/{id}")
  @Operation(summary = "Get segment detail")
  public ApiResponse<Map<String, Object>> get(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("id") UUID id) {
    return ApiResponse.ok(segments.get(principal, id));
  }

  @PostMapping("/{id}/compute")
  @Operation(summary = "Enqueue segment recompute")
  public ResponseEntity<ApiResponse<Map<String, Object>>> compute(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("id") UUID id) {
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(ApiResponse.ok(segments.enqueueCompute(principal, id)));
  }

  @DeleteMapping("/{id}")
  @Operation(summary = "Delete custom segment")
  public ApiResponse<Map<String, Object>> delete(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("id") UUID id) {
    return ApiResponse.ok(segments.delete(principal, id));
  }

  @GetMapping("/{id}/customers")
  @Operation(summary = "List customers in segment")
  public ApiResponse<Map<String, Object>> customers(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer limit,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false) String order) {
    SegmentService.PagedResult result =
        segments.listCustomers(principal, id, page, limit, sort, order);
    return ApiResponse.ok(result.data(), result.meta());
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record CreateSegmentRequest(
      String name, String description, List<SegmentCriterion> criteria) {}
}
