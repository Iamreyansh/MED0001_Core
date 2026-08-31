package com.nammamedmate.auth.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface PharmacyAssignmentJpaRepository extends JpaRepository<PharmacyAssignmentEntity, UUID> {

  @Query(
      value =
          "SELECT a.id, a.staff_id, a.pharmacy_id, r.code AS role_code, a.is_active,"
              + " a.joined_at, a.removed_at, p.name AS pharmacy_name"
              + " FROM pharmacy_staff_assignment a"
              + " JOIN pharmacy_roles r ON r.id = a.role_id"
              + " JOIN pharmacies p ON p.id = a.pharmacy_id"
              + " WHERE a.staff_id = :staffId AND a.is_active = TRUE"
              + " ORDER BY a.joined_at ASC",
      nativeQuery = true)
  List<AssignmentProjection> findActiveByStaffIdOrderByJoinedAt(@Param("staffId") UUID staffId);

  @Query(
      value =
          "SELECT a.id, a.staff_id, a.pharmacy_id, r.code AS role_code, a.is_active,"
              + " a.joined_at, a.removed_at, p.name AS pharmacy_name"
              + " FROM pharmacy_staff_assignment a"
              + " JOIN pharmacy_roles r ON r.id = a.role_id"
              + " JOIN pharmacies p ON p.id = a.pharmacy_id"
              + " WHERE a.staff_id = :staffId AND a.pharmacy_id = :pharmacyId AND a.is_active = TRUE"
              + " LIMIT 1",
      nativeQuery = true)
  Optional<AssignmentProjection> findActiveByStaffIdAndPharmacyId(
      @Param("staffId") UUID staffId, @Param("pharmacyId") UUID pharmacyId);

  @Query(
      value =
          "SELECT a.id, a.staff_id, a.pharmacy_id, r.code AS role_code, a.is_active,"
              + " a.joined_at, a.removed_at, p.name AS pharmacy_name"
              + " FROM pharmacy_staff_assignment a"
              + " JOIN pharmacy_roles r ON r.id = a.role_id"
              + " JOIN pharmacies p ON p.id = a.pharmacy_id"
              + " WHERE a.staff_id = :staffId AND a.pharmacy_id = :pharmacyId"
              + " LIMIT 1",
      nativeQuery = true)
  Optional<AssignmentProjection> findByStaffIdAndPharmacyId(
      @Param("staffId") UUID staffId, @Param("pharmacyId") UUID pharmacyId);

  @Query(
      value =
          "SELECT s.id AS staff_id, s.name, s.email, s.phone, s.status, r.code AS role_code,"
              + " a.is_active, a.joined_at,"
              + " CASE WHEN s.pos_pin_hash IS NULL THEN FALSE ELSE TRUE END AS pos_pin_set"
              + " FROM pharmacy_staff_assignment a"
              + " JOIN pharmacy_staff s ON s.id = a.staff_id"
              + " JOIN pharmacy_roles r ON r.id = a.role_id"
              + " WHERE a.pharmacy_id = :pharmacyId AND s.deleted_at IS NULL"
              + " ORDER BY a.joined_at DESC",
      nativeQuery = true)
  List<StaffDirectoryProjection> listDirectory(@Param("pharmacyId") UUID pharmacyId);

  @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
  @Query(
      value =
          "UPDATE pharmacy_staff_assignment SET is_active = FALSE, removed_at = :removedAt"
              + " WHERE staff_id = :staffId AND pharmacy_id = :pharmacyId AND is_active = TRUE",
      nativeQuery = true)
  int deactivate(
      @Param("staffId") UUID staffId,
      @Param("pharmacyId") UUID pharmacyId,
      @Param("removedAt") java.time.Instant removedAt);

  @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
  @Query(
      value =
          "UPDATE pharmacy_staff_assignment SET is_active = TRUE, removed_at = NULL, role_id = :roleId"
              + " WHERE staff_id = :staffId AND pharmacy_id = :pharmacyId",
      nativeQuery = true)
  int reactivate(
      @Param("staffId") UUID staffId,
      @Param("pharmacyId") UUID pharmacyId,
      @Param("roleId") UUID roleId);

  interface StaffDirectoryProjection {
    UUID getStaff_id();

    String getName();

    String getEmail();

    String getPhone();

    String getStatus();

    String getRole_code();

    boolean getIs_active();

    java.time.Instant getJoined_at();

    boolean getPos_pin_set();
  }

  interface AssignmentProjection {
    UUID getId();

    UUID getStaff_id();

    UUID getPharmacy_id();

    String getRole_code();

    boolean getIs_active();

    java.time.Instant getJoined_at();

    java.time.Instant getRemoved_at();

    String getPharmacy_name();
  }
}
