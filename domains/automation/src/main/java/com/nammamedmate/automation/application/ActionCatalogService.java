package com.nammamedmate.automation.application;

import com.nammamedmate.automation.application.port.out.ActionRegistryPort;
import com.nammamedmate.automation.domain.ActionDefinition;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ActionCatalogService {

  private final ActionRegistryPort registry;

  public ActionCatalogService(ActionRegistryPort registry) {
    this.registry = registry;
  }

  public Map<String, Object> list() {
    List<ActionDefinition> actions = registry.listAll();
    List<Map<String, Object>> rows = new ArrayList<>();
    for (ActionDefinition a : actions) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("action_id", a.actionId());
      row.put("category", a.category());
      row.put("name", a.name());
      row.put("description", a.description());
      row.put("required_params", a.requiredParams());
      row.put("optional_params", a.optionalParams());
      row.put("is_reversible", a.reversible());
      row.put("always_require_approval", a.alwaysRequireApproval());
      if (a.autoApprovalLimitPaise() != null) {
        row.put("auto_approval_limit_paise", a.autoApprovalLimitPaise());
      }
      rows.add(row);
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("actions", rows);
    data.put("total_actions", rows.size());
    return data;
  }
}
