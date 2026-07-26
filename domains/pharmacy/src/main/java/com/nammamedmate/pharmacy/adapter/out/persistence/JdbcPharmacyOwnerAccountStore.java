package com.nammamedmate.pharmacy.adapter.out.persistence;

import com.nammamedmate.pharmacy.application.port.out.PharmacyOwnerAccountStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcPharmacyOwnerAccountStore implements PharmacyOwnerAccountStore {

  private final JdbcTemplate jdbc;

  public JdbcPharmacyOwnerAccountStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void createOwner(OwnerCreate cmd) {
    // INVITED until email OTP verified — blocks password login (PharmacyLoginService)
    jdbc.update(
        """
        INSERT INTO pharmacy_staff (
          id, name, email, phone, password_hash, status, failed_login_attempts,
          created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, 'INVITED', 0, ?, ?)
        """,
        cmd.staffId(),
        cmd.name(),
        cmd.email(),
        cmd.phone(),
        cmd.passwordHash(),
        Timestamp.from(cmd.now()),
        Timestamp.from(cmd.now()));
    jdbc.update(
        """
        INSERT INTO pharmacy_staff_assignment (
          id, staff_id, pharmacy_id, role_id, is_active, joined_at
        ) VALUES (?, ?, ?, ?, TRUE, ?)
        """,
        UUID.randomUUID(),
        cmd.staffId(),
        cmd.pharmacyId(),
        cmd.roleId() == null ? OWNER_ROLE_ID : cmd.roleId(),
        Timestamp.from(cmd.now()));
  }

  @Override
  public void activateOwner(UUID staffId, Instant now) {
    jdbc.update(
        """
        UPDATE pharmacy_staff
        SET status = 'ACTIVE', updated_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        Timestamp.from(now),
        staffId);
  }

  @Override
  public java.util.Optional<UUID> findStaffIdByEmail(String email) {
    var rows =
        jdbc.query(
            """
            SELECT id FROM pharmacy_staff
            WHERE lower(email) = lower(?) AND deleted_at IS NULL
            LIMIT 1
            """,
            (rs, n) -> (UUID) rs.getObject("id"),
            email);
    return rows.stream().findFirst();
  }

  @Override
  public boolean emailTakenPlatformWide(String email) {
    // customers have no email column yet (EPIC-002 profile uses phone only)
    return count(
            """
            SELECT (
              (SELECT COUNT(*) FROM pharmacy_staff WHERE lower(email) = ? AND deleted_at IS NULL)
              + (SELECT COUNT(*) FROM admin_staff WHERE lower(email) = ? AND deleted_at IS NULL)
              + (SELECT COUNT(*) FROM pharmacies WHERE lower(email) = ? AND deleted_at IS NULL)
            )
            """,
            email,
            email,
            email)
        > 0;
  }

  @Override
  public boolean phoneTakenPlatformWide(String phone) {
    // admin_staff has no phone column
    return count(
            """
            SELECT (
              (SELECT COUNT(*) FROM customers WHERE phone = ? AND deleted_at IS NULL)
              + (SELECT COUNT(*) FROM pharmacy_staff WHERE phone = ? AND deleted_at IS NULL)
              + (SELECT COUNT(*) FROM pharmacies WHERE phone = ? AND deleted_at IS NULL)
            )
            """,
            phone,
            phone,
            phone)
        > 0;
  }

  private int count(String sql, Object... args) {
    Integer n = jdbc.queryForObject(sql, Integer.class, args);
    return n == null ? 0 : n;
  }
}
