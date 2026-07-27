package com.nammamedmate.pharmacy.application.port.out;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Normalised result from external KYC API adapters (GSTN, drug control, FSSAI). */
public interface KycGovernmentApiPort {

  record KycCheckResult(
      String status,
      String apiProvider,
      Map<String, Object> requestPayload,
      Map<String, Object> responsePayload,
      Map<String, Object> details,
      List<Map<String, Object>> adminFlags,
      boolean transientError) {

    public KycCheckResult {
      requestPayload = copyMap(requestPayload);
      responsePayload = responsePayload == null ? null : copyMap(responsePayload);
      details = details == null ? null : copyMap(details);
      adminFlags = adminFlags == null ? List.of() : List.copyOf(adminFlags);
    }

    private static Map<String, Object> copyMap(Map<String, Object> source) {
      if (source == null || source.isEmpty()) {
        return Map.of();
      }
      return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
  }

  interface GstinVerificationPort {
    KycCheckResult verifyGstin(String gstin, String platformBusinessName);
  }

  interface DrugLicenceVerificationPort {
    KycCheckResult verifyDrugLicence(String licenceNumber, String stateCode);
  }

  interface FssaiVerificationPort {
    KycCheckResult verifyFssai(String fssaiNumber);
  }

  /** Helper for drug licence expiry enforcement in the service layer. */
  static boolean isLicenceExpiringSoon(LocalDate expiryDate, LocalDate today) {
    if (expiryDate == null) {
      return false;
    }
    return !expiryDate.isAfter(today.plusDays(90));
  }
}
