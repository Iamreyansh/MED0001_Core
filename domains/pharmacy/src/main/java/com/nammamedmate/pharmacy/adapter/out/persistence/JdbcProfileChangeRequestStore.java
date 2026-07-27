package com.nammamedmate.pharmacy.adapter.out.persistence;

import com.nammamedmate.pharmacy.application.port.out.ProfileChangeRequestStore;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcProfileChangeRequestStore implements ProfileChangeRequestStore {

  private final JdbcTemplate jdbc;

  public JdbcProfileChangeRequestStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(ChangeRequestRecord record) {
    jdbc.update(
        """
        INSERT INTO profile_change_requests (
          id, pharmacy_id, field_name, old_value, new_value, status, reviewed_by, reviewed_at, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        record.id(),
        record.pharmacyId(),
        record.fieldName(),
        record.oldValue(),
        record.newValue(),
        record.status(),
        record.reviewedBy(),
        record.reviewedAt() == null ? null : Timestamp.from(record.reviewedAt()),
        Timestamp.from(record.createdAt()));
  }
}
