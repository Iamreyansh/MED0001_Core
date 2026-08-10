package com.nammamedmate.prescription.application.port.out;

import java.util.Optional;

/** Resolves drug schedule classification (H / H1 / X) without catalogue domain deps. */
public interface CatalogueSchedulePort {

  Optional<String> resolveSchedule(String medicineName);
}
