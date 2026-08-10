package com.nammamedmate.prescription.domain;

import java.time.Instant;
import java.time.Period;
import java.util.UUID;

public record ScheduleDrugRegisterEntry(
    UUID id,
    int sno,
    UUID pharmacyId,
    String schedule,
    UUID rxId,
    String rxReferenceNo,
    UUID orderId,
    String patientName,
    Integer patientAge,
    String prescriberName,
    String prescriberRegNo,
    String drugName,
    String batchNo,
    int quantityIssued,
    String unit,
    int runningBalance,
    String pharmacyLicenseNo,
    String dispensedByName,
    UUID dispensedByUserId,
    Instant dispensedAt,
    Instant retentionExpiresAt,
    boolean archived,
    Instant createdAt) {

  public static Period retentionFor(String schedule) {
    if ("X".equalsIgnoreCase(schedule)) {
      return Period.ofYears(5);
    }
    return Period.ofYears(3);
  }

  public static boolean isRegisterSchedule(String schedule) {
    return "H1".equalsIgnoreCase(schedule) || "X".equalsIgnoreCase(schedule);
  }
}
