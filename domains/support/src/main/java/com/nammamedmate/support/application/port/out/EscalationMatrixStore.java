package com.nammamedmate.support.application.port.out;

import com.nammamedmate.support.domain.EscalationRule;
import com.nammamedmate.support.domain.SlaLevel;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EscalationMatrixStore {

  List<EscalationRule> listAll();

  Optional<EscalationRule> findByLevel(SlaLevel level);

  List<SlaLevel> updateRules(List<RulePatch> patches, UUID updatedBy, Instant updatedAt);

  record RulePatch(
      SlaLevel level, Integer autoEscalateAfterMinutes, List<String> notificationChannels) {
    public RulePatch {
      notificationChannels =
          notificationChannels == null ? null : List.copyOf(notificationChannels);
    }
  }
}
