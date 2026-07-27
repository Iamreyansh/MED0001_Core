package com.nammamedmate.pharmacy.application.port.out;

import java.util.Optional;
import java.util.UUID;

public interface PincodeZoneStore {

  Optional<UUID> findZoneIdByPincode(String pincode);
}
