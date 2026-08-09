package com.nammamedmate.integration.adapter.out.client;

import com.nammamedmate.integration.application.port.out.FssaiClientPort;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;

/**
 * Deterministic FSSAI stub.
 *
 * <ul>
 *   <li>licence containing {@code UNKNOWN} or {@code NOTFOUND} → empty (not found)
 *   <li>licence containing {@code DOWN} → MANUAL_REVIEW_REQUIRED
 *   <li>otherwise → ACTIVE
 * </ul>
 */
public final class StubFssaiClient implements FssaiClientPort {

  private final Clock clock;

  public StubFssaiClient(Clock clock) {
    this.clock = clock;
  }

  @Override
  public Optional<FssaiResult> verify(String licenceNumber) {
    String lic = licenceNumber == null ? "" : licenceNumber.toUpperCase(Locale.ROOT);
    if (lic.contains("UNKNOWN") || lic.contains("NOTFOUND")) {
      return Optional.empty();
    }
    if (lic.contains("DOWN")) {
      return Optional.of(
          new FssaiResult(false, false, true, null, null, null, "MANUAL_REVIEW_REQUIRED"));
    }
    LocalDate expiry = LocalDate.of(LocalDate.now(clock).getYear() + 2, 1, 31);
    return Optional.of(
        new FssaiResult(
            true,
            true,
            false,
            "Apollo Health & Lifestyle Ltd",
            "CENTRAL_LICENCE",
            expiry,
            "ACTIVE"));
  }
}
