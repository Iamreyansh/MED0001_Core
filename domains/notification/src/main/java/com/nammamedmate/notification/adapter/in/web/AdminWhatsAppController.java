package com.nammamedmate.notification.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.notification.application.WhatsAppAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/notifications/whatsapp")
@Tag(name = "Admin WhatsApp")
public class AdminWhatsAppController {

  private final WhatsAppAdminService admin;

  public AdminWhatsAppController(WhatsAppAdminService admin) {
    this.admin = admin;
  }

  @GetMapping("/templates")
  @Operation(summary = "List WhatsApp message templates and Meta approval status")
  public ApiResponse<Map<String, Object>> listTemplates(
      @RequestParam(value = "category", required = false) String category,
      @RequestParam(value = "status", required = false) String status) {
    return ApiResponse.ok(admin.listTemplates(category, status));
  }

  @PostMapping("/templates")
  @Operation(summary = "Submit a new WhatsApp template to Meta for approval")
  public ResponseEntity<ApiResponse<Map<String, Object>>> createTemplate(
      @RequestBody(required = false) CreateTemplateRequest body) {
    CreateTemplateRequest req =
        body == null ? new CreateTemplateRequest(null, null, null, null, null, null, null) : body;
    Map<String, Object> data =
        admin.submitTemplate(
            req.name(),
            req.category(),
            req.language(),
            req.body(),
            req.header(),
            req.footer(),
            req.buttons());
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(data));
  }

  @GetMapping("/logs")
  @Operation(summary = "List WhatsApp delivery logs")
  public ApiResponse<Map<String, Object>> listLogs(
      @RequestParam(value = "to_phone", required = false) String toPhone,
      @RequestParam(value = "template_name", required = false) String templateName,
      @RequestParam(value = "status", required = false) String status,
      @RequestParam(value = "date_from", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant dateFrom,
      @RequestParam(value = "date_to", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant dateTo,
      @RequestParam(value = "page", required = false) Integer page,
      @RequestParam(value = "limit", required = false) Integer limit) {
    WhatsAppAdminService.LogPage result =
        admin.listLogs(toPhone, templateName, status, dateFrom, dateTo, page, limit);
    return ApiResponse.ok(
        result.data(), PaginationMeta.of(result.page(), result.limit(), result.total()));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record CreateTemplateRequest(
      String name,
      String category,
      String language,
      String body,
      Map<String, Object> header,
      String footer,
      List<Map<String, Object>> buttons) {
    public CreateTemplateRequest {
      header = header == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(header));
      buttons =
          buttons == null
              ? null
              : Collections.unmodifiableList(
                  buttons.stream()
                      .map(
                          b ->
                              b == null
                                  ? Map.<String, Object>of()
                                  : Collections.unmodifiableMap(new LinkedHashMap<>(b)))
                      .toList());
    }
  }
}
