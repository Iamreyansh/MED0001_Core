package com.nammamedmate.customer.application.port.out;

/** Runtime wallet credit cap; platform config bridge in apps/api. */
public interface WalletCreditLimitPort {

  long maxCreditPaise();
}
