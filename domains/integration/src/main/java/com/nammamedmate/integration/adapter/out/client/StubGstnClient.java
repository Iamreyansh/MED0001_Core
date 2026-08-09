package com.nammamedmate.integration.adapter.out.client;

import com.nammamedmate.integration.application.port.out.GstnClientPort;
import com.nammamedmate.kernel.error.AppException;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;

/**
 * Deterministic GSTN stub.
 *
 * <ul>
 *   <li>GSTIN containing {@code 9999} → {@code GSTN_API_UNAVAILABLE}
 *   <li>GSTIN containing {@code 0000} → not found
 *   <li>otherwise → ACTIVE trade details
 * </ul>
 */
public final class StubGstnClient implements GstnClientPort {

  private final boolean forceUnavailable;

  public StubGstnClient() {
    this(false);
  }

  public StubGstnClient(boolean forceUnavailable) {
    this.forceUnavailable = forceUnavailable;
  }

  @Override
  public Optional<GstnResult> verify(String gstin) {
    if (forceUnavailable) {
      throw unavailable();
    }
    String upper = gstin == null ? "" : gstin.toUpperCase(Locale.ROOT);
    if (upper.contains("9999")) {
      throw unavailable();
    }
    if (upper.contains("0000")) {
      return Optional.empty();
    }
    String stateCode = upper.length() >= 2 ? upper.substring(0, 2) : "";
    String state = "29".equals(stateCode) ? "Karnataka" : stateName(stateCode);
    boolean apollo = upper.startsWith("27AAPFU") || upper.startsWith("29ABCDE");
    return Optional.of(
        new GstnResult(
            true,
            true,
            apollo ? "Apollo Pharmacy India Ltd" : "Registered Trade Name",
            apollo ? "Apollo Hospitals Enterprise Limited" : "Registered Legal Name",
            "ACTIVE",
            "REGULAR",
            state,
            stateCode,
            LocalDate.of(2018, 4, 1)));
  }

  private static AppException unavailable() {
    return new AppException("GSTN_API_UNAVAILABLE", "GSTN portal unreachable", 503);
  }

  private static String stateName(String code) {
    return switch (code) {
      case "27" -> "Maharashtra";
      case "33" -> "Tamil Nadu";
      case "07" -> "Delhi";
      default -> "India";
    };
  }
}
