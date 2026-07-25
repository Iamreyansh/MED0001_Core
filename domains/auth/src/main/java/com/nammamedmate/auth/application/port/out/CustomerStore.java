package com.nammamedmate.auth.application.port.out;

import java.util.Optional;

public interface CustomerStore {

  Optional<CustomerRecord> findByPhone(String phone);

  CustomerRecord save(CustomerRecord customer);
}
