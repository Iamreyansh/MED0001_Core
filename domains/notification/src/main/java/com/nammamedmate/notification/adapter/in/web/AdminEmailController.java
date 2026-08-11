package com.nammamedmate.notification.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.notification.application.EmailAdminService;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/notifications/email")
@Tag(name = "Admin email")
public class AdminEmailController {

  private final EmailAdminService admin;

  public AdminEmailController(EmailAdminService admin) {
    this.admin = admin;
  }

  @GetMapping("/templates")
  @Operation(summary = "List email templates with open/click rates")
  public ApiResponse<Map<String, Object>> listTemplates(
      @RequestParam(value = "category", required = false) String category,
      @RequestParam(value = "is_active", required = false) Boolean isActive) {
    return ApiResponse.ok(admin.listTemplates(category, isActive));
  }

  @PostMapping("/templates")
  @Operation(summary = "Create or update an email template")
  public ResponseEntity<ApiResponse<Map<String, Object>>> upsertTemplate(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) UpsertTemplateRequest body) {
    UpsertTemplateRequest req =
        body == null ? new UpsertTemplateRequest(null, null, null, null, null, null) : body;
    Map<String, Object> data =
        admin.upsertTemplate(
            principal.subject(),
            req.templateId(),
            req.name(),
            req.subject(),
            req.htmlBody(),
            req.textBody(),
            req.category());
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(data));
  }

  @GetMapping("/logs")
  @Operation(summary = "List email delivery logs")
  public ApiResponse<Map<String, Object>> listLogs(
      @RequestParam(value = "to_email", required = false) String toEmail,
      @RequestParam(value = "template_id", required = false) String templateId,
      @RequestParam(value = "status", required = false) String status,
      @RequestParam(value = "date_from", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant dateFrom,
      @RequestParam(value = "date_to", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant dateTo,
      @RequestParam(value = "page", required = false) Integer page,
      @RequestParam(value = "limit", required = false) Integer limit) {
    EmailAdminService.LogPage result =
        admin.listLogs(toEmail, templateId, status, dateFrom, dateTo, page, limit);
    return ApiResponse.ok(
        result.data(), PaginationMeta.of(result.page(), result.limit(), result.total()));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record UpsertTemplateRequest(
      String name,
      String templateId,
      String subject,
      String htmlBody,
      String textBody,
      String category) {}
}
