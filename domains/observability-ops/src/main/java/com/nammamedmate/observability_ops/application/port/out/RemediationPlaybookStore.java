package com.nammamedmate.observability_ops.application.port.out;

import com.nammamedmate.observability_ops.domain.AlertType;
import com.nammamedmate.observability_ops.domain.RemediationPlaybook;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface RemediationPlaybookStore {

  List<RemediationPlaybook> findAll();

  Optional<RemediationPlaybook> findById(UUID id);

  Optional<RemediationPlaybook> findByAlertType(AlertType alertType);

  RemediationPlaybook update(
      UUID id, boolean enabled, Map<String, Object> threshold, UUID updatedBy, Instant updatedAt);

  void touchLastTriggered(UUID id, Instant at);
}
