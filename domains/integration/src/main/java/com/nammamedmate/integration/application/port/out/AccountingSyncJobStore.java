package com.nammamedmate.integration.application.port.out;

import com.nammamedmate.integration.domain.AccountingSyncJob;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountingSyncJobStore {

  void insert(AccountingSyncJob job);

  void update(AccountingSyncJob job);

  Optional<AccountingSyncJob> findById(UUID id);

  boolean hasActiveJob(UUID pharmacyId);

  List<AccountingSyncJob> findQueued(int limit);
}
