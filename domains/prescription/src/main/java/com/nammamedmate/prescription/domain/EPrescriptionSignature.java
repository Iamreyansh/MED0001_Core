package com.nammamedmate.prescription.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * SHA-256 digital signature for e-prescriptions.
 *
 * <p>Hash = SHA256(doctor_id + "|" + patient_name + "|" + medicines_json_canonical + "|" +
 * issued_at_iso8601). Canonical medicines JSON: sorted by name, alphabetical keys, no whitespace.
 */
public final class EPrescriptionSignature {

  private EPrescriptionSignature() {}

  public static String compute(
      UUID doctorId, String patientName, List<MedicinePrescribed> medicines, Instant issuedAt) {
    String payload =
        doctorId
            + "|"
            + (patientName == null ? "" : patientName)
            + "|"
            + canonicalMedicinesJson(medicines)
            + "|"
            + issuedAt.toString();
    return sha256Hex(payload);
  }

  public static boolean verify(
      String storedHash,
      UUID doctorId,
      String patientName,
      List<MedicinePrescribed> medicines,
      Instant issuedAt) {
    if (storedHash == null || storedHash.isBlank()) {
      return false;
    }
    return storedHash.equalsIgnoreCase(compute(doctorId, patientName, medicines, issuedAt));
  }

  /** Canonical JSON: sort medicines by name; object keys alphabetical; no whitespace. */
  public static String canonicalMedicinesJson(List<MedicinePrescribed> medicines) {
    List<MedicinePrescribed> sorted = new ArrayList<>(medicines == null ? List.of() : medicines);
    sorted.sort(
        Comparator.comparing(
            (MedicinePrescribed m) -> m.name() == null ? "" : m.name(),
            String.CASE_INSENSITIVE_ORDER));
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < sorted.size(); i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append(canonicalMedicineObject(sorted.get(i)));
    }
    sb.append(']');
    return sb.toString();
  }

  private static String canonicalMedicineObject(MedicinePrescribed m) {
    Map<String, Object> keys = new TreeMap<>();
    keys.put("dosage", m.dosage() == null ? "" : m.dosage());
    if (m.durationDays() != null) {
      keys.put("duration_days", m.durationDays());
    }
    keys.put("frequency", m.frequency() == null ? "" : m.frequency());
    keys.put("name", m.name() == null ? "" : m.name());
    if (m.notes() != null) {
      keys.put("notes", m.notes());
    }
    keys.put("quantity", m.quantity());
    keys.put("unit", m.unit() == null ? "" : m.unit());
    return toCompactJsonObject(keys);
  }

  private static String toCompactJsonObject(Map<String, Object> keys) {
    StringBuilder sb = new StringBuilder("{");
    boolean first = true;
    for (Map.Entry<String, Object> e : keys.entrySet()) {
      if (!first) {
        sb.append(',');
      }
      first = false;
      sb.append('"').append(escape(e.getKey())).append("\":");
      Object v = e.getValue();
      if (v instanceof Number n) {
        sb.append(n);
      } else {
        sb.append('"').append(escape(String.valueOf(v))).append('"');
      }
    }
    sb.append('}');
    return sb.toString();
  }

  private static String escape(String s) {
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private static String sha256Hex(String payload) {
    return digestHex("SHA-256", payload);
  }

  /** Package-visible for coverage of the impossible-algorithm branch. */
  static String digestHex(String algorithm, String payload) {
    try {
      MessageDigest md = MessageDigest.getInstance(algorithm);
      byte[] dig = md.digest(payload.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(dig.length * 2);
      for (byte b : dig) {
        hex.append(String.format("%02x", b));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(algorithm + " not available", e);
    }
  }

  /** Medicine line as stored on e-Rx (EPIC-009 STORY-004). */
  public record MedicinePrescribed(
      String name,
      String dosage,
      String frequency,
      int quantity,
      String unit,
      Integer durationDays,
      String notes) {

    public Map<String, Object> toApiMap() {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("name", name);
      m.put("dosage", dosage);
      m.put("frequency", frequency);
      m.put("quantity", quantity);
      m.put("unit", unit);
      m.put("duration_days", durationDays);
      m.put("notes", notes);
      return m;
    }
  }
}
