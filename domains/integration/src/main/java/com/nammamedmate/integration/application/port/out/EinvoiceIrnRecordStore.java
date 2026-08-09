package com.nammamedmate.integration.application.port.out;

import com.nammamedmate.integration.domain.EinvoiceIrnRecord;
import java.util.Optional;
import java.util.UUID;

public interface EinvoiceIrnRecordStore {

  void insert(EinvoiceIrnRecord record);

  void update(EinvoiceIrnRecord record);

  Optional<EinvoiceIrnRecord> findByIrn(String irn);

  Optional<EinvoiceIrnRecord> findByDocumentKey(
      String sellerGstin,
      String buyerGstin,
      String documentType,
      String financialYear,
      String invoiceNumber);

  Optional<EinvoiceIrnRecord> findById(UUID id);
}
