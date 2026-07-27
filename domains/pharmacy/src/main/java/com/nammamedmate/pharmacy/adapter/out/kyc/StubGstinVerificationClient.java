package com.nammamedmate.pharmacy.adapter.out.kyc;

import com.nammamedmate.pharmacy.application.port.out.KycGovernmentApiPort;
import com.nammamedmate.pharmacy.application.port.out.KycGovernmentApiPort.KycCheckResult;
import com.nammamedmate.pharmacy.domain.BusinessNameMatcher;
import com.nammamedmate.pharmacy.domain.KycRequestSanitizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Offline GSTIN verification for local/CI.
 *
 * <p>ponytail: real GSTN API when EPIC-022 government integration is wired.
 *
 * <ul>
 *   <li>gstin containing {@code invalid} → FAIL (non-retryable)
 *   <li>gstin containing {@code error} or {@code timeout} → ERROR (transient)
 *   <li>gstin containing {@code mismatch} → PASS with different registered name
 *   <li>otherwise → PASS
 * </ul>
 */
public final class StubGstinVerificationClient
    implements KycGovernmentApiPort.GstinVerificationPort {

  public static final String API_PROVIDER = "GSTN_SANDBOX_API";

  @Override
  public KycCheckResult verifyGstin(String gstin, String platformBusinessName) {
    Map<String, Object> rawRequest = new LinkedHashMap<>();
    rawRequest.put("gstin", gstin);
    rawRequest.put("api_key", "stub-not-logged");
    Map<String, Object> request = KycRequestSanitizer.sanitise(rawRequest);
    String lower = gstin == null ? "" : gstin.toLowerCase(Locale.ROOT);

    if (lower.contains("invalid")) {
      return new KycCheckResult(
          "FAIL",
          API_PROVIDER,
          request,
          Map.of("error", "invalid_gstin"),
          Map.of("gstin", gstin, "registration_status", "INVALID"),
          List.of(),
          false);
    }
    if (lower.contains("error") || lower.contains("timeout")) {
      return new KycCheckResult(
          "ERROR",
          API_PROVIDER,
          request,
          Map.of("error", "upstream_unavailable"),
          null,
          List.of(),
          true);
    }

    String registeredName =
        lower.contains("mismatch")
            ? "ALPHA BETA GAMMA DELTA EPSILON ZETA ETA THETA ENTERPRISES"
            : (platformBusinessName == null ? "REGISTERED NAME" : platformBusinessName)
                .toUpperCase(Locale.ROOT);

    Map<String, Object> details = new LinkedHashMap<>();
    details.put("gstin", gstin);
    details.put("business_name_registered", registeredName);
    details.put("business_name_platform", platformBusinessName);
    details.put("registration_status", "ACTIVE");
    details.put("gstin_type", "Regular");
    details.put("filing_status", "Filed");
    details.put("state_code", gstin != null && gstin.length() >= 2 ? gstin.substring(0, 2) : "");

    List<Map<String, Object>> flags = new ArrayList<>();
    String nameMatch = "MATCH";
    if (BusinessNameMatcher.isSignificantMismatch(platformBusinessName, registeredName)) {
      nameMatch = "WARN";
      flags.add(
          Map.of(
              "flag",
              "BUSINESS_NAME_MISMATCH",
              "detail",
              "GSTIN-registered name '"
                  + registeredName
                  + "' differs from platform name '"
                  + platformBusinessName
                  + "'. Please verify manually.",
              "severity",
              "WARN"));
    }
    details.put("name_match", nameMatch);

    return new KycCheckResult(
        "PASS",
        API_PROVIDER,
        request,
        Map.of("status", "ACTIVE", "trade_name", registeredName),
        details,
        flags,
        false);
  }
}
