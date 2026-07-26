package com.nammamedmate.auth.application.port.out;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PharmacyRoleStore {

  List<PharmacyRoleRecord> listSystemRoles();

  List<PharmacyRoleRecord> listCustomByPharmacy(UUID pharmacyId);

  Optional<PharmacyRoleRecord> findById(UUID id);

  Optional<PharmacyRoleRecord> findSystemByCode(String code);

  Optional<PharmacyRoleRecord> findActiveByPharmacyAndCode(UUID pharmacyId, String code);

  PharmacyRoleRecord save(PharmacyRoleRecord role);

  int countActiveStaff(UUID roleId, UUID pharmacyId);
}
