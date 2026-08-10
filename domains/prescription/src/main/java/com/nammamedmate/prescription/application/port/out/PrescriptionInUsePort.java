package com.nammamedmate.prescription.application.port.out;

import java.util.UUID;

/** True when prescription is linked to a non-CANCELLED order. */
public interface PrescriptionInUsePort {

  boolean isInUse(UUID prescriptionId);
}
