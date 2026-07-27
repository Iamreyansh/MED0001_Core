package com.nammamedmate.pharmacy.adapter.out.kyc;

import com.nammamedmate.pharmacy.application.port.out.KycGovernmentApiPort;
import com.nammamedmate.pharmacy.application.port.out.KycGovernmentApiPort.KycCheckResult;
import com.nammamedmate.pharmacy.domain.KycRequestSanitizer;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Offline FSSAI verification for local/CI.
 *
 * <p>ponytail: real FSSAI portal API via EPIC-022.
 *
 * <ul>
 *   <li>fssai containing {@code fail} → FAIL
 *   <li>fssai containing {@code error} → ERROR (transient)
 *   <li>otherwise → PASS
 * </ul>
 */
public final class StubFssaiVerificationClient
    implements KycGovernmentApiPort.FssaiVerificationPort {

  public static final String API_PROVIDER = "FSSAI_PORTAL_API";

  @Override
  public KycCheckResult verifyFssai(String fssaiNumber) {
    Map<String, Object> rawRequest = new LinkedHashMap<>();
    rawRequest.put("licence_number", fssaiNumber);
    rawRequest.put("secret", "stub-not-logged");
    Map<String, Object> request = KycRequestSanitizer.sanitise(rawRequest);
    String lower = fssaiNumber == null ? "" : fssaiNumber.toLowerCase(Locale.ROOT);

    if (lower.contains("fail")) {
      return new KycCheckResult(
          "FAIL",
          API_PROVIDER,
          request,
          Map.of("licence_status", "CANCELLED"),
          Map.of("licence_number", fssaiNumber, "licence_status", "CANCELLED"),
          List.of(),
          false);
    }
    if (lower.contains("error")) {
      return new KycCheckResult(
          "ERROR", API_PROVIDER, request, Map.of("error", "upstream_500"), null, List.of(), true);
    }

    Map<String, Object> details = new LinkedHashMap<>();
    details.put("licence_number", fssaiNumber);
    details.put("business_name", "REGISTERED FBO");
    details.put("licence_status", "ACTIVE");
    details.put("expiry_date", LocalDate.now().plusYears(3).toString());
    details.put("category", "Retail");

    return new KycCheckResult(
        "PASS", API_PROVIDER, request, Map.of("status", "ACTIVE"), details, List.of(), false);
  }
}
