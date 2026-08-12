package com.nammamedmate.automation.adapter.out.persistence;

import com.nammamedmate.automation.application.port.out.RuleLookupPort;
import com.nammamedmate.automation.application.port.out.RuleStorePort;
import com.nammamedmate.automation.domain.RuleSnapshot;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Bridges RuleStorePort → RuleLookupPort for the evaluator / ActiveRuleCache. */
@Component
public class JdbcRuleLookupAdapter implements RuleLookupPort {

  private final RuleStorePort store;

  public JdbcRuleLookupAdapter(RuleStorePort store) {
    this.store = store;
  }

  @Override
  public Optional<RuleSnapshot> findById(UUID ruleId) {
    return store
        .findById(ruleId)
        .map(com.nammamedmate.automation.domain.AutomationRule::toSnapshot);
  }

  @Override
  public List<RuleSnapshot> listActive() {
    return store.listActiveOrSimulating();
  }
}
