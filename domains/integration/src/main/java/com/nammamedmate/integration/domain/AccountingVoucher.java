package com.nammamedmate.integration.domain;

import java.time.LocalDate;
import java.util.UUID;

/** Platform accounting voucher for Zoho/Tally sync (money in paise). */
public record AccountingVoucher(
    UUID platformId,
    String recordType,
    String voucherNumber,
    LocalDate voucherDate,
    String partyName,
    String partyGstin,
    long taxablePaise,
    long gstPaise,
    long totalPaise) {}
