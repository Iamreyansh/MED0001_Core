package com.nammamedmate.automation.application.port.out;

import com.nammamedmate.automation.domain.TriggerDefinition;
import java.util.List;
import java.util.Optional;

public interface TriggerRegistryPort {

  List<TriggerDefinition> listActive(String categoryOrNull);

  Optional<TriggerDefinition> findById(String triggerId);
}
