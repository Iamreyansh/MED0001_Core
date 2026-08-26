package com.nammamedmate.integration.application.port.out;

import com.nammamedmate.integration.domain.CashfreeBeneficiary;
import java.util.Optional;
import java.util.UUID;

public interface CashfreeBeneficiaryStore {

  void insert(CashfreeBeneficiary account);

  void deactivate(UUID id);

  Optional<CashfreeBeneficiary> findActiveByEntity(String entityType, UUID entityId);

  Optional<CashfreeBeneficiary> findByBeneficiaryId(String beneficiaryId);
}
