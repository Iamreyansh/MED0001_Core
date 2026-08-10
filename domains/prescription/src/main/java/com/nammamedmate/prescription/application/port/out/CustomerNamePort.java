package com.nammamedmate.prescription.application.port.out;

import java.util.Optional;
import java.util.UUID;

public interface CustomerNamePort {

  Optional<String> findName(UUID customerId);
}
