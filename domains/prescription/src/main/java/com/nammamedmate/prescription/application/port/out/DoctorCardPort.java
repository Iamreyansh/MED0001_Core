package com.nammamedmate.prescription.application.port.out;

import java.util.Optional;
import java.util.UUID;

public interface DoctorCardPort {

  record DoctorCard(String name, String qualification, String registrationNo, boolean verified) {}

  Optional<DoctorCard> findForPrescription(
      UUID rxId, String type, String doctorName, UUID teleconsultId);
}
