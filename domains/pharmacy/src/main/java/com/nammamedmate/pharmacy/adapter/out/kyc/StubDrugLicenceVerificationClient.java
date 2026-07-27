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
 * Offline drug licence verification for local/CI.
 *
 * <p>ponytail: state-specific drug control APIs via EPIC-022.
 *
 * <ul>
 *   <li>licence containing {@code fail} → FAIL
 *   <li>licence containing {@code error} → ERROR (transient)
 *   <li>licence containing {@code expiring} → PASS with expiry &lt; 90 days (service enforces FAIL)
 *   <li>unsupported state (not MH/KA/TN/DL) → FAIL manual review
 *   <li>otherwise → PASS
 * </ul>
 */
public final class StubDrugLicenceVerificationClient
    implements KycGovernmentApiPort.DrugLicenceVerificationPort {

  public static final String API_PROVIDER = "MH_DRUG_CONTROL_API";
  private static final List<String> SUPPORTED_STATES = List.of("MH", "KA", "TN", "DL");

  @Override
  public KycCheckResult verifyDrugLicence(String licenceNumber, String stateCode) {
    Map<String, Object> rawRequest = new LinkedHashMap<>();
    rawRequest.put("licence_number", licenceNumber);
    rawRequest.put("state", stateCode);
    rawRequest.put("authorization", "stub-not-logged");
    Map<String, Object> request = KycRequestSanitizer.sanitise(rawRequest);
    String licence = licenceNumber == null ? "" : licenceNumber.toLowerCase(Locale.ROOT);
    String state = stateCode == null ? "" : stateCode.toUpperCase(Locale.ROOT);

    if (!SUPPORTED_STATES.contains(state)) {
      return new KycCheckResult(
          "FAIL",
          API_PROVIDER,
          request,
          Map.of("error", "unsupported_state"),
          Map.of("reason", "MANUAL_REVIEW_REQUIRED", "state", state),
          List.of(),
          false);
    }
    if (licence.contains("fail")) {
      return new KycCheckResult(
          "FAIL",
          API_PROVIDER,
          request,
          Map.of("licence_status", "CANCELLED"),
          Map.of("licence_number", licenceNumber, "licence_status", "CANCELLED", "state", state),
          List.of(),
          false);
    }
    if (licence.contains("error")) {
      return new KycCheckResult(
          "ERROR", API_PROVIDER, request, Map.of("error", "upstream_500"), null, List.of(), true);
    }

    LocalDate expiry =
        licence.contains("expiring") ? LocalDate.now().plusDays(30) : LocalDate.now().plusYears(2);

    Map<String, Object> details = new LinkedHashMap<>();
    details.put("licence_number", licenceNumber);
    details.put("licence_status", "ACTIVE");
    details.put("expiry_date", expiry.toString());
    details.put("state", state);

    return new KycCheckResult(
        "PASS", API_PROVIDER, request, Map.of("status", "ACTIVE"), details, List.of(), false);
  }
}
