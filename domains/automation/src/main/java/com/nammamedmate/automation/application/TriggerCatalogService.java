package com.nammamedmate.automation.application;

import com.nammamedmate.automation.application.port.out.TriggerRegistryPort;
import com.nammamedmate.automation.domain.TriggerDefinition;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class TriggerCatalogService {

  private final TriggerRegistryPort registry;

  public TriggerCatalogService(TriggerRegistryPort registry) {
    this.registry = registry;
  }

  public Map<String, Object> list(String category) {
    List<TriggerDefinition> triggers = registry.listActive(category);
    List<Map<String, Object>> rows = new ArrayList<>();
    for (TriggerDefinition t : triggers) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("trigger_id", t.triggerId());
      row.put("category", t.category());
      row.put("name", t.name());
      row.put("description", t.description());
      row.put("parameters", t.parameters());
      row.put("available_conditions", t.availableConditions());
      row.put("available_context_vars", t.availableContextVars());
      rows.add(row);
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("triggers", rows);
    data.put("total_triggers", rows.size());
    return data;
  }
}
