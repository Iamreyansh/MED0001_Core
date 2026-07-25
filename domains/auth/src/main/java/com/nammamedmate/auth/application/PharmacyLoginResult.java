package com.nammamedmate.auth.application;

import com.nammamedmate.auth.application.port.out.PharmacyAssignmentRecord;
import com.nammamedmate.auth.application.port.out.PharmacyRecord;
import com.nammamedmate.auth.application.port.out.PharmacyStaffRecord;
import java.util.List;

public record PharmacyLoginResult(
    String accessToken,
    String refreshToken,
    long accessTtlSeconds,
    long refreshTtlSeconds,
    PharmacyRecord activePharmacy,
    String roleInActivePharmacy,
    PharmacyStaffRecord staff,
    List<PharmacyAssignmentRecord> assignments) {

  public PharmacyLoginResult {
    assignments = List.copyOf(assignments);
  }
}
