package com.nammamedmate.marketing.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.marketing.application.CampaignService;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/campaigns")
@Tag(name = "Admin campaigns")
public class AdminCampaignController {

  private final CampaignService campaigns;

  public AdminCampaignController(CampaignService campaigns) {
    this.campaigns = campaigns;
  }

  @GetMapping
  @Operation(summary = "List campaigns")
  public ApiResponse<Map<String, Object>> list(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String channel,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer limit,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false) String order) {
    CampaignService.PagedResult result =
        campaigns.list(principal, status, channel, page, limit, sort, order);
    return ApiResponse.ok(result.data(), result.meta());
  }

  @GetMapping("/cost-estimate")
  @Operation(summary = "Estimate campaign cost for channel + segment")
  public ApiResponse<Map<String, Object>> costEstimate(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam String channel,
      @RequestParam("segment_id") UUID segmentId,
      @RequestParam(value = "message_length", required = false) Integer messageLength) {
    return ApiResponse.ok(campaigns.costEstimate(principal, channel, segmentId, messageLength));
  }

  @PostMapping
  @Operation(summary = "Create campaign")
  public ResponseEntity<ApiResponse<Map<String, Object>>> create(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) CreateCampaignRequest body) {
    CreateCampaignRequest req =
        body == null
            ? new CreateCampaignRequest(
                null, null, null, null, null, null, null, null, null, null, null)
            : body;
    Map<String, Object> data =
        campaigns.create(
            principal,
            new CampaignService.CreateCommand(
                req.name(),
                req.channel(),
                req.segmentId(),
                req.messageTemplateId(),
                req.subject(),
                req.body(),
                req.ctaLabel(),
                req.ctaLink(),
                req.scheduledAt(),
                req.estimatedCost(),
                req.budgetCap()));
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(data));
  }

  @GetMapping("/{id}")
  @Operation(summary = "Get campaign detail")
  public ApiResponse<Map<String, Object>> get(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("id") UUID id) {
    return ApiResponse.ok(campaigns.get(principal, id));
  }

  @PatchMapping("/{id}")
  @Operation(summary = "Update draft/scheduled/paused campaign")
  public ApiResponse<Map<String, Object>> patch(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestBody(required = false) PatchCampaignRequest body) {
    PatchCampaignRequest req =
        body == null
            ? new PatchCampaignRequest(
                null, null, null, null, null, null, null, null, null, null, null)
            : body;
    return ApiResponse.ok(
        campaigns.patch(
            principal,
            id,
            new CampaignService.PatchCommand(
                req.name(),
                req.channel(),
                req.segmentId(),
                req.messageTemplateId(),
                req.subject(),
                req.body(),
                req.ctaLabel(),
                req.ctaLink(),
                req.scheduledAt(),
                req.estimatedCost(),
                req.budgetCap())));
  }

  @PostMapping("/{id}/launch")
  @Operation(summary = "Launch campaign")
  public ApiResponse<Map<String, Object>> launch(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("id") UUID id) {
    return ApiResponse.ok(campaigns.launch(principal, id));
  }

  @PostMapping("/{id}/pause")
  @Operation(summary = "Pause running campaign")
  public ApiResponse<Map<String, Object>> pause(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("id") UUID id) {
    return ApiResponse.ok(campaigns.pause(principal, id));
  }

  @PostMapping("/{id}/resume")
  @Operation(summary = "Resume paused campaign")
  public ApiResponse<Map<String, Object>> resume(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("id") UUID id) {
    return ApiResponse.ok(campaigns.resume(principal, id));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record CreateCampaignRequest(
      String name,
      String channel,
      UUID segmentId,
      UUID messageTemplateId,
      String subject,
      String body,
      String ctaLabel,
      String ctaLink,
      Instant scheduledAt,
      Number estimatedCost,
      Number budgetCap) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record PatchCampaignRequest(
      String name,
      String channel,
      UUID segmentId,
      UUID messageTemplateId,
      String subject,
      String body,
      String ctaLabel,
      String ctaLink,
      Instant scheduledAt,
      Number estimatedCost,
      Number budgetCap) {}
}
