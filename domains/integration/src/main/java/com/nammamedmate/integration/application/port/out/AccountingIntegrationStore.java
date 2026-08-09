package com.nammamedmate.integration.application.port.out;

import com.nammamedmate.integration.domain.AccountingIntegration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountingIntegrationStore {

  Optional<AccountingIntegration> findByPharmacyId(UUID pharmacyId);

  void upsert(AccountingIntegration integration);

  List<AccountingIntegration> findDueAutoSync(Instant now, int limit);
}
