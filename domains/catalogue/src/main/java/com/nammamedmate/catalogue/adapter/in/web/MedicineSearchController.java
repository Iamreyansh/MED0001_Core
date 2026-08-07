package com.nammamedmate.catalogue.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.catalogue.application.MedicineSearchService;
import com.nammamedmate.catalogue.application.MedicineSearchService.Envelope;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/catalogue")
@Tag(name = "Catalogue search")
public class MedicineSearchController {

  private final MedicineSearchService service;

  public MedicineSearchController(MedicineSearchService service) {
    this.service = service;
  }

  @GetMapping("/search")
  @Operation(summary = "Customer medicine search / autocomplete")
  public Map<String, Object> search(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam("q") String q,
      @RequestParam(value = "category_id", required = false) UUID categoryId,
      @RequestParam(value = "schedule", required = false) String schedule,
      @RequestParam(value = "is_rx_only", required = false) Boolean isRxOnly,
      @RequestParam(value = "lat", required = false) Double lat,
      @RequestParam(value = "lng", required = false) Double lng,
      @RequestParam(value = "pharmacy_id", required = false) UUID pharmacyId,
      @RequestParam(value = "zone_id", required = false) UUID zoneId,
      @RequestParam(value = "pincode", required = false) String pincode,
      @RequestParam(value = "autocomplete", defaultValue = "false") boolean autocomplete,
      @RequestParam(value = "show_oos", defaultValue = "false") boolean showOos,
      @RequestParam(value = "include_banned", defaultValue = "false") boolean includeBanned,
      @RequestParam(value = "page", required = false) Integer page,
      @RequestParam(value = "limit", required = false) Integer limit,
      HttpServletRequest request) {
    Envelope env =
        service.search(
            principal,
            q,
            categoryId,
            schedule,
            isRxOnly,
            lat,
            lng,
            pharmacyId,
            zoneId,
            pincode,
            autocomplete,
            showOos,
            includeBanned,
            page,
            limit,
            clientIp(request));
    return envelope(env);
  }

  @GetMapping("/{id}")
  @Operation(summary = "Public medicine detail")
  public Map<String, Object> detail(
      @PathVariable("id") UUID id,
      @RequestParam(value = "lat", required = false) Double lat,
      @RequestParam(value = "lng", required = false) Double lng,
      @RequestParam(value = "zone_id", required = false) UUID zoneId,
      @RequestParam(value = "pincode", required = false) String pincode,
      @RequestParam(value = "show_oos", defaultValue = "false") boolean showOos,
      HttpServletRequest request) {
    return envelope(service.getDetail(lat, lng, zoneId, pincode, id, showOos, clientIp(request)));
  }

  @GetMapping("/substitutes/{medicineId}")
  @Operation(summary = "One-hop substitute medicines")
  public Map<String, Object> substitutes(
      @PathVariable("medicineId") UUID medicineId, HttpServletRequest request) {
    return envelope(service.substitutes(medicineId, clientIp(request)));
  }

  @PostMapping("/check-availability")
  @Operation(summary = "Check medicine availability at a pharmacy")
  public Map<String, Object> checkAvailability(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody CheckAvailabilityRequest body,
      HttpServletRequest request) {
    CheckAvailabilityRequest req = body == null ? new CheckAvailabilityRequest(null, null) : body;
    return envelope(
        service.checkAvailability(
            principal, req.medicineIds(), req.pharmacyId(), clientIp(request)));
  }

  private static Map<String, Object> envelope(Envelope env) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("success", true);
    body.put("data", env.data());
    body.put("meta", env.meta() == null ? Map.of() : env.meta());
    return body;
  }

  private static String clientIp(HttpServletRequest request) {
    if (request == null) {
      return null;
    }
    String remote = request.getRemoteAddr();
    return remote == null || remote.isBlank() ? null : remote.trim();
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record CheckAvailabilityRequest(List<UUID> medicineIds, UUID pharmacyId) {}
}
