package com.nammamedmate.auth.application.port.out;

import java.util.Optional;
import java.util.UUID;

public interface CustomerStore {

  Optional<CustomerRecord> findByPhone(String phone);

  Optional<CustomerRecord> findById(UUID id);

  CustomerRecord save(CustomerRecord customer);
}
