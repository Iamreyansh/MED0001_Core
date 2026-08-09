package com.nammamedmate.rider.application.port.out;

import java.util.UUID;

/**
 * Hook after admin confirms a COD deposit (EPIC-012 ledger {@code COD_DEPOSIT}). Stub in rider;
 * bridged in apps/api.
 */
public interface CodDepositConfirmedPort {

  void onDepositConfirmed(UUID depositId, UUID riderId, long amountPaise);
}
