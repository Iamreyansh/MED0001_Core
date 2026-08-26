package com.nammamedmate.integration.application.port.out;

import com.nammamedmate.integration.domain.CashfreePaymentRecord;
import java.util.Optional;
import java.util.UUID;

public interface CashfreePaymentRecordStore {

  void insert(CashfreePaymentRecord record);

  void update(CashfreePaymentRecord record);

  Optional<CashfreePaymentRecord> findById(UUID id);

  Optional<CashfreePaymentRecord> findByGatewayOrderId(String gatewayOrderId);

  Optional<CashfreePaymentRecord> findByGatewayPaymentId(String gatewayPaymentId);
}
