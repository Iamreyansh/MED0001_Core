package com.nammamedmate.automation.application.port.out;

import com.nammamedmate.automation.domain.ActionDefinition;
import java.util.List;
import java.util.Optional;

public interface ActionRegistryPort {

  List<ActionDefinition> listAll();

  Optional<ActionDefinition> findById(String actionId);
}
