package com.nammamedmate.auth.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface PharmacyRoleJpaRepository extends JpaRepository<PharmacyRoleEntity, UUID> {

  List<PharmacyRoleEntity> findBySystemTrueAndDeletedAtIsNullOrderByCodeAsc();

  List<PharmacyRoleEntity> findByPharmacyIdAndDeletedAtIsNullOrderByCodeAsc(UUID pharmacyId);

  Optional<PharmacyRoleEntity> findByIdAndDeletedAtIsNull(UUID id);

  Optional<PharmacyRoleEntity> findBySystemTrueAndCodeAndDeletedAtIsNull(String code);

  Optional<PharmacyRoleEntity> findByPharmacyIdAndCodeAndDeletedAtIsNull(
      UUID pharmacyId, String code);

  @Query(
      value =
          "SELECT COUNT(*) FROM pharmacy_staff_assignment a"
              + " WHERE a.role_id = :roleId AND a.pharmacy_id = :pharmacyId AND a.is_active = TRUE",
      nativeQuery = true)
  int countActiveStaff(@Param("roleId") UUID roleId, @Param("pharmacyId") UUID pharmacyId);

  @Query(
      value =
          "SELECT COUNT(*) FROM pharmacy_staff_assignment a"
              + " WHERE a.role_id = :roleId AND a.is_active = TRUE",
      nativeQuery = true)
  int countActiveStaffGlobal(@Param("roleId") UUID roleId);
}
