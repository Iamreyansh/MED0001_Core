package com.nammamedmate.pos.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record InvoiceSettings(
    UUID pharmacyId,
    InvoiceTemplate template,
    String accentColor,
    String logoUrl,
    String signatureUrl,
    String documentTitle,
    String invoicePrefix,
    String signatoryLabel,
    Map<String, Object> bankDetails,
    String termsAndConditions,
    String footerNote,
    boolean showMrpSavings,
    boolean showDoctor,
    boolean showHsn,
    boolean printBankDetails,
    Instant updatedAt) {

  public InvoiceSettings {
    bankDetails = bankDetails == null ? null : Map.copyOf(bankDetails);
  }
}
