package com.nammamedmate.automation.application.port.out;

import com.nammamedmate.automation.domain.RuleSnapshot;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Active/simulating rule lookup for the evaluator. */
public interface RuleLookupPort {

  Optional<RuleSnapshot> findById(UUID ruleId);

  /** ACTIVE and SIMULATING rules (non-deleted). */
  List<RuleSnapshot> listActive();
}
