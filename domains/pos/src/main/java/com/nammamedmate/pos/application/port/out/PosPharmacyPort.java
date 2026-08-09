package com.nammamedmate.pos.application.port.out;

import java.util.Optional;
import java.util.UUID;

public interface PosPharmacyPort {

  record PharmacyInfo(
      String name, String address, String phone, String gstin, String drugLicence) {}

  Optional<PharmacyInfo> findById(UUID pharmacyId);
}
