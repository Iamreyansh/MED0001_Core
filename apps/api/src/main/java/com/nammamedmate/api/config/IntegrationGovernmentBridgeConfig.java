package com.nammamedmate.api.config;

import com.nammamedmate.integration.application.GovernmentApiService;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.pharmacy.application.port.out.KycGovernmentApiPort.DrugLicenceVerificationPort;
import com.nammamedmate.pharmacy.application.port.out.KycGovernmentApiPort.FssaiVerificationPort;
import com.nammamedmate.pharmacy.application.port.out.KycGovernmentApiPort.GstinVerificationPort;
import com.nammamedmate.pharmacy.application.port.out.KycGovernmentApiPort.KycCheckResult;
import com.nammamedmate.pharmacy.domain.BusinessNameMatcher;
import com.nammamedmate.pharmacy.domain.KycRequestSanitizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Composition-root bridge: pharmacy KYC government ports → integration {@link
 * GovernmentApiService}.
 */
@Configuration
public class IntegrationGovernmentBridgeConfig {

  @Bean
  @Primary
  GstinVerificationPort integrationGstinVerificationPort(GovernmentApiService gov) {
    return (gstin, platformBusinessName) -> {
      Map<String, Object> request =
          KycRequestSanitizer.sanitise(Map.of("gstin", gstin == null ? "" : gstin));
      try {
        Map<String, Object> data = gov.verifyGstin(gstin, "PHARMACY", null);
        String registered =
            str(data.get("trade_name"), str(data.get("legal_name"), platformBusinessName));
        Map<String, Object> details = new LinkedHashMap<>(data);
        details.put("business_name_registered", registered);
        details.put("business_name_platform", platformBusinessName);
        List<Map<String, Object>> flags = new ArrayList<>();
        String nameMatch = "MATCH";
        if (BusinessNameMatcher.isSignificantMismatch(platformBusinessName, registered)) {
          nameMatch = "WARN";
          flags.add(
              Map.of(
                  "flag",
                  "BUSINESS_NAME_MISMATCH",
                  "detail",
                  "GSTIN-registered name differs from platform name",
                  "severity",
                  "WARN"));
        }
        details.put("name_match", nameMatch);
        return new KycCheckResult("PASS", "GSTN_API", request, copy(data), details, flags, false);
      } catch (AppException e) {
        return mapGstnError(e, request);
      }
    };
  }

  @Bean
  @Primary
  DrugLicenceVerificationPort integrationDrugLicenceVerificationPort(GovernmentApiService gov) {
    return (licenceNumber, stateCode) -> {
      Map<String, Object> request =
          KycRequestSanitizer.sanitise(
              Map.of(
                  "licence_number",
                  licenceNumber == null ? "" : licenceNumber,
                  "state",
                  stateCode == null ? "" : stateCode));
      try {
        Map<String, Object> data =
            gov.verifyDrugLicence(licenceNumber, stateCode, "RETAIL", "PHARMACY", null);
        if ("PENDING".equals(data.get("status"))) {
          return new KycCheckResult(
              "ERROR", "DRUG_REGISTRY_API", request, copy(data), null, List.of(), true);
        }
        if ("MANUAL_REVIEW_REQUIRED".equals(data.get("status"))) {
          return new KycCheckResult(
              "ERROR",
              "DRUG_REGISTRY_API",
              request,
              copy(data),
              Map.of("reason", "MANUAL_REVIEW_REQUIRED"),
              List.of(),
              true);
        }
        boolean expired = Boolean.TRUE.equals(data.get("is_expired"));
        String status = expired ? "FAIL" : "PASS";
        Map<String, Object> details = new LinkedHashMap<>(data);
        details.put("licence_status", data.get("status"));
        return new KycCheckResult(
            status, "DRUG_REGISTRY_API", request, copy(data), details, List.of(), false);
      } catch (AppException e) {
        return new KycCheckResult(
            "ERROR",
            "DRUG_REGISTRY_API",
            request,
            Map.of("error", e.code()),
            null,
            List.of(),
            e.httpStatus() >= 500 || e.httpStatus() == 429);
      }
    };
  }

  @Bean
  @Primary
  FssaiVerificationPort integrationFssaiVerificationPort(GovernmentApiService gov) {
    return fssaiNumber -> {
      Map<String, Object> request =
          KycRequestSanitizer.sanitise(
              Map.of("licence_number", fssaiNumber == null ? "" : fssaiNumber));
      try {
        Map<String, Object> data = gov.verifyFssai(fssaiNumber, "PHARMACY", null);
        if ("MANUAL_REVIEW_REQUIRED".equals(data.get("status"))) {
          return new KycCheckResult(
              "ERROR",
              "FSSAI_PORTAL_API",
              request,
              copy(data),
              Map.of("reason", "MANUAL_REVIEW_REQUIRED"),
              List.of(),
              true);
        }
        boolean expired = Boolean.TRUE.equals(data.get("is_expired"));
        Map<String, Object> details = new LinkedHashMap<>(data);
        details.put("licence_status", data.get("status"));
        return new KycCheckResult(
            expired ? "FAIL" : "PASS",
            "FSSAI_PORTAL_API",
            request,
            copy(data),
            details,
            List.of(),
            false);
      } catch (AppException e) {
        boolean notFound = "FSSAI_LICENCE_NOT_FOUND".equals(e.code());
        return new KycCheckResult(
            notFound ? "FAIL" : "ERROR",
            "FSSAI_PORTAL_API",
            request,
            Map.of("error", e.code()),
            notFound ? Map.of("licence_status", "NOT_FOUND") : null,
            List.of(),
            !notFound);
      }
    };
  }

  private static KycCheckResult mapGstnError(AppException e, Map<String, Object> request) {
    boolean transientError =
        "GSTN_API_UNAVAILABLE".equals(e.code()) || "GSTN_RATE_LIMIT".equals(e.code());
    String status = transientError ? "ERROR" : "FAIL";
    return new KycCheckResult(
        status,
        "GSTN_API",
        request,
        Map.of("error", e.code()),
        Map.of("registration_status", e.code()),
        List.of(),
        transientError);
  }

  private static String str(Object value, String fallback) {
    if (value == null) {
      return fallback;
    }
    String s = value.toString();
    return s.isBlank() ? fallback : s;
  }

  private static Map<String, Object> copy(Map<String, Object> source) {
    return source == null ? Map.of() : new LinkedHashMap<>(source);
  }
}
