package com.nammamedmate.integration.adapter.out.client;

import com.nammamedmate.integration.application.port.out.DrugRegistryClientPort;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Deterministic drug-registry stub.
 *
 * <ul>
 *   <li>state not in KA/MH/TN/DL → async PENDING (poll)
 *   <li>licence containing {@code DOWN} → MANUAL_REVIEW_REQUIRED
 *   <li>licence containing {@code EXPIRED} → expired ACTIVE→EXPIRED
 *   <li>licence containing {@code EXPIRING} → expiry within 30 days
 *   <li>otherwise → ACTIVE valid licence
 * </ul>
 */
public final class StubDrugRegistryClient implements DrugRegistryClientPort {

  private static final Set<String> SYNC_STATES =
      Set.of("KARNATAKA", "KA", "MAHARASHTRA", "MH", "TAMIL NADU", "TN", "DELHI", "DL");

  private final Clock clock;

  public StubDrugRegistryClient(Clock clock) {
    this.clock = clock;
  }

  @Override
  public DrugLicenceResult verify(String licenceNumber, String state, String licenceType) {
    String lic = licenceNumber == null ? "" : licenceNumber.toUpperCase(Locale.ROOT);
    String st = state == null ? "" : state.trim();
    String type = licenceType == null || licenceType.isBlank() ? "RETAIL" : licenceType;
    String stateKey = st.toUpperCase(Locale.ROOT);

    // Unsupported states are async; DOWN on those is resolved on poll.
    if (!SYNC_STATES.contains(stateKey)) {
      return new DrugLicenceResult(
          true, false, false, false, null, null, null, List.of(), st, type, "PENDING");
    }
    if (lic.contains("DOWN")) {
      return new DrugLicenceResult(
          false,
          true,
          false,
          false,
          null,
          null,
          null,
          List.of(),
          st,
          type,
          "MANUAL_REVIEW_REQUIRED");
    }

    LocalDate today = LocalDate.now(clock);
    LocalDate issued = today.minusYears(5);
    LocalDate expiry;
    String status;
    boolean valid;
    if (lic.contains("EXPIRED")) {
      expiry = today.minusDays(1);
      status = "EXPIRED";
      valid = false;
    } else if (lic.contains("EXPIRING")) {
      expiry = today.plusDays(20);
      status = "ACTIVE";
      valid = true;
    } else {
      expiry = today.plusYears(2);
      status = "ACTIVE";
      valid = true;
    }
    return new DrugLicenceResult(
        false,
        false,
        true,
        valid,
        "Apollo Pharmacy India Ltd",
        issued,
        expiry,
        List.of("SCHEDULE_H", "SCHEDULE_H1", "OTC"),
        st,
        type,
        status);
  }
}
