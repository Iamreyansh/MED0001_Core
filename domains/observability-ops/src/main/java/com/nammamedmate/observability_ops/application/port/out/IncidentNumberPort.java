package com.nammamedmate.observability_ops.application.port.out;

import java.time.LocalDate;

/** Daily INC-YYYYMMDD-NNN sequence (Redis with atomic in-memory fallback). */
public interface IncidentNumberPort {

  String next(LocalDate day);
}
