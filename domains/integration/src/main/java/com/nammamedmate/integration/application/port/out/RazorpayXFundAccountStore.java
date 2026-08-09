package com.nammamedmate.integration.application.port.out;

import com.nammamedmate.integration.domain.RazorpayXFundAccount;
import java.util.Optional;
import java.util.UUID;

public interface RazorpayXFundAccountStore {

  void insert(RazorpayXFundAccount account);

  void deactivate(UUID id);

  Optional<RazorpayXFundAccount> findActiveByEntity(String entityType, UUID entityId);

  Optional<RazorpayXFundAccount> findByFundAccountId(String fundAccountId);
}
