package com.nammamedmate.integration.application.port.out;

import com.nammamedmate.integration.domain.AccountingVoucher;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Cross-domain sales/purchases/GST source (bridged from POS/ERP in apps/api). */
public interface AccountingDataPort {

  List<AccountingVoucher> sales(UUID pharmacyId, LocalDate from, LocalDate to);

  List<AccountingVoucher> purchases(UUID pharmacyId, LocalDate from, LocalDate to);

  List<AccountingVoucher> expenses(UUID pharmacyId, LocalDate from, LocalDate to);

  List<AccountingVoucher> gstEntries(UUID pharmacyId, LocalDate from, LocalDate to);
}
