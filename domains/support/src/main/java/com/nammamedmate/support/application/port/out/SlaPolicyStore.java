package com.nammamedmate.support.application.port.out;

import com.nammamedmate.support.domain.SlaLevel;
import com.nammamedmate.support.domain.SlaPolicy;
import com.nammamedmate.support.domain.TicketCategory;
import com.nammamedmate.support.domain.TicketPriority;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SlaPolicyStore {

  List<SlaPolicy> listAll();

  Optional<SlaPolicy> findById(UUID id);

  /** Category-specific (incl. ANY) wins over ALL/{priority}. */
  Optional<SlaPolicy> resolve(TicketCategory category, TicketPriority priority);

  SlaPolicy update(
      UUID id,
      Integer firstResponseMinutes,
      Integer resolutionMinutes,
      SlaLevel slaLevel,
      UUID updatedBy,
      Instant updatedAt);
}
