package com.nammamedmate.pos.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.pos.application.InvoiceService;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
@RequestMapping("/api/v1/pharmacy")
@Tag(name = "Pharmacy Invoices")
public class PharmacyInvoiceController {

  private final InvoiceService invoiceService;

  public PharmacyInvoiceController(InvoiceService invoiceService) {
    this.invoiceService = invoiceService;
  }

  @GetMapping("/invoices")
  @Operation(summary = "List pharmacy invoices or export EXCEL/PDF")
  public Object list(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(value = "from_date", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate fromDate,
      @RequestParam(value = "to_date", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate toDate,
      @RequestParam(value = "payment_method", required = false) String paymentMethod,
      @RequestParam(value = "channel", required = false) String channel,
      @RequestParam(value = "q", required = false) String q,
      @RequestParam(value = "page", required = false) Integer page,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "export", required = false) String export,
      @RequestParam(value = "pharmacy_id", required = false) UUID pharmacyId) {
    Object result =
        invoiceService.list(
            principal,
            fromDate,
            toDate,
            paymentMethod,
            channel,
            q,
            page,
            limit,
            export,
            pharmacyId);
    if (result instanceof InvoiceService.FileExport file) {
      return ResponseEntity.ok()
          .header(
              HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.filename() + "\"")
          .contentType(MediaType.parseMediaType(file.contentType()))
          .body(file.bytes());
    }
    InvoiceService.ListResult pageResult = (InvoiceService.ListResult) result;
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("success", true);
    body.put("data", pageResult.data());
    body.put("meta", pageResult.meta());
    return body;
  }

  @GetMapping("/invoices/{invoiceId}")
  @Operation(summary = "Invoice detail with GST breakdown")
  public ApiResponse<Map<String, Object>> detail(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable UUID invoiceId) {
    return ApiResponse.ok(invoiceService.getDetail(principal, invoiceId));
  }

  @GetMapping("/invoices/{invoiceId}/pdf")
  @Operation(summary = "Download invoice PDF (application/pdf binary)")
  public ResponseEntity<byte[]> pdf(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable UUID invoiceId,
      @RequestParam(value = "template", required = false) String template) {
    InvoiceService.FileExport file = invoiceService.pdf(principal, invoiceId, template);
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + file.filename() + "\"")
        .contentType(MediaType.APPLICATION_PDF)
        .body(file.bytes());
  }

  @PostMapping("/invoices/{invoiceId}/share")
  @Operation(summary = "Share invoice via WhatsApp/SMS/Email")
  public ApiResponse<Map<String, Object>> share(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable UUID invoiceId,
      @RequestBody(required = false) ShareRequest body) {
    ShareRequest req = body == null ? new ShareRequest(null, null) : body;
    return ApiResponse.ok(
        invoiceService.share(principal, invoiceId, req.channel(), req.recipientPhoneOrEmail()));
  }

  @GetMapping("/invoice-settings")
  @Operation(summary = "Get invoice template settings")
  public ApiResponse<Map<String, Object>> getSettings(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(invoiceService.getSettings(principal));
  }

  @PatchMapping("/invoice-settings")
  @Operation(summary = "Update invoice template settings (owner)")
  public ApiResponse<Map<String, Object>> patchSettings(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) Map<String, Object> body) {
    return ApiResponse.ok(invoiceService.patchSettings(principal, body));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record ShareRequest(String channel, String recipientPhoneOrEmail) {}
}
