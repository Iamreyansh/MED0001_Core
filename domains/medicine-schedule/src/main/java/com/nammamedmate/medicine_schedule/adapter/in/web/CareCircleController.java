package com.nammamedmate.medicine_schedule.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.medicine_schedule.application.CareCircleService;
import com.nammamedmate.medicine_schedule.application.CareCircleService.CreateCommand;
import com.nammamedmate.medicine_schedule.application.CareCircleService.UpdateCommand;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/schedule/care-circle")
@Tag(name = "Care circle")
public class CareCircleController {

  private final CareCircleService service;

  public CareCircleController(CareCircleService service) {
    this.service = service;
  }

  @GetMapping
  @Operation(summary = "List care circle members")
  public ApiResponse<Map<String, Object>> list(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(service.list(principal));
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Add a family member to the care circle")
  public ApiResponse<Map<String, Object>> create(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) MemberRequest body) {
    return ApiResponse.ok(
        service.create(
            principal,
            body == null
                ? null
                : new CreateCommand(
                    body.name(),
                    body.age(),
                    body.relationship(),
                    body.avatarEmoji(),
                    body.avatarColor())));
  }

  @PatchMapping("/{member_id}")
  @Operation(summary = "Update care circle member details")
  public ApiResponse<Map<String, Object>> update(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("member_id") UUID memberId,
      @RequestBody(required = false) MemberRequest body) {
    return ApiResponse.ok(
        service.update(
            principal,
            memberId,
            body == null
                ? null
                : new UpdateCommand(
                    body.name(),
                    body.age(),
                    body.relationship(),
                    body.avatarEmoji(),
                    body.avatarColor())));
  }

  @DeleteMapping("/{member_id}")
  @Operation(summary = "Remove a family member from the care circle")
  public ApiResponse<Map<String, Object>> delete(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("member_id") UUID memberId) {
    return ApiResponse.ok(service.delete(principal, memberId));
  }

  @GetMapping("/{member_id}/summary")
  @Operation(summary = "Member schedule summary")
  public ApiResponse<Map<String, Object>> summary(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("member_id") UUID memberId) {
    return ApiResponse.ok(service.summary(principal, memberId));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record MemberRequest(
      String name, Integer age, String relationship, String avatarEmoji, String avatarColor) {}
}
