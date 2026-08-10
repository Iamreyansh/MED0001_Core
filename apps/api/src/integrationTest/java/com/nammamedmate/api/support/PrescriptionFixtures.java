package com.nammamedmate.api.support;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Seeds prescription rows for ITs that previously relied on StubPrescriptionAdapter. */
public final class PrescriptionFixtures {

  private PrescriptionFixtures() {}

  public static UUID insertVerified(JdbcTemplate jdbc, UUID customerId) {
    return insert(jdbc, customerId, UUID.randomUUID(), "VERIFIED", false);
  }

  public static UUID insertVerified(JdbcTemplate jdbc, UUID customerId, UUID prescriptionId) {
    return insert(jdbc, customerId, prescriptionId, "VERIFIED", false);
  }

  public static UUID insertExpired(JdbcTemplate jdbc, UUID customerId, UUID prescriptionId) {
    return insert(jdbc, customerId, prescriptionId, "EXPIRED", true);
  }

  private static UUID insert(
      JdbcTemplate jdbc, UUID customerId, UUID id, String status, boolean expired) {
    jdbc.update(
        """
        INSERT INTO prescription (
          id, customer_id, type, status, s3_key, file_size_bytes, mime_type,
          patient_name, notes, doctor_name, prescription_date, source,
          medicines_extracted, associated_order_id, teleconsult_id, expires_at,
          rejection_reason, created_at, updated_at, deleted_at
        ) VALUES (
          ?, ?, 'UPLOADED', ?, ?, 1024, 'image/jpeg',
          'IT Patient', null, 'Dr IT', CURRENT_DATE, 'UPLOAD',
          '[{"name":"Metformin 500mg","quantity":"60 tablets","dosage":"1-0-1","schedule":"H"}]'::jsonb,
          null, null,
          CASE WHEN ? THEN NOW() - INTERVAL '1 day' ELSE NOW() + INTERVAL '180 days' END,
          null, NOW(), NOW(), null
        )
        ON CONFLICT (id) DO UPDATE SET
          customer_id = EXCLUDED.customer_id,
          status = EXCLUDED.status,
          expires_at = EXCLUDED.expires_at,
          deleted_at = NULL,
          updated_at = NOW()
        """,
        id,
        customerId,
        status,
        "prescriptions/it/" + id + ".jpg",
        expired);
    return id;
  }
}
