package com.nammamedmate.order.application.port.out;

import java.util.UUID;

/** Wallet balance estimate for cart bill (deduction is EPIC-010 STORY-004 / EPIC-012). */
public interface WalletBalancePort {

  long balancePaise(UUID customerId);
}
