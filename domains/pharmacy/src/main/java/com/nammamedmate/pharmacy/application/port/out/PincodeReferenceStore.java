package com.nammamedmate.pharmacy.application.port.out;

import java.util.Optional;

public interface PincodeReferenceStore {

  record PincodeRecord(String pincode, String stateCode, String stateName, boolean serviceable) {}

  Optional<PincodeRecord> findServiceable(String pincode);
}
