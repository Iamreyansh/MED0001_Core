package com.nammamedmate.integration.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.integration.application.EinvoiceServiceAcTest;
import com.nammamedmate.kernel.error.AppException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InvoiceSchemaValidatorTest {

  @Test
  void acceptsValidInvoice() {
    InvoiceSchemaValidator.validate(EinvoiceServiceAcTest.validInvoice());
  }

  @Test
  void rejectsNullEmptyAndSupplyType() {
    assertThatThrownBy(() -> InvoiceSchemaValidator.validate(null))
        .extracting(ex -> ((AppException) ex).details().get("field"))
        .isEqualTo("invoice_data");
    assertThatThrownBy(() -> InvoiceSchemaValidator.validate(Map.of()))
        .extracting(ex -> ((AppException) ex).details().get("field"))
        .isEqualTo("invoice_data");

    Map<String, Object> b2c = EinvoiceServiceAcTest.validInvoice();
    b2c.put("supply_type", "B2C");
    assertField(b2c, "supply_type");

    Map<String, Object> blankSupply = EinvoiceServiceAcTest.validInvoice();
    blankSupply.put("supply_type", "");
    assertField(blankSupply, "supply_type");
  }

  @Test
  void rejectsBadGstinDateItemsTax() {
    Map<String, Object> badSeller = EinvoiceServiceAcTest.validInvoice();
    badSeller.put("seller_gstin", "29ABCDE1234F1Z0");
    assertField(badSeller, "seller_gstin");

    Map<String, Object> blankBuyer = EinvoiceServiceAcTest.validInvoice();
    blankBuyer.put("buyer_gstin", "");
    assertField(blankBuyer, "buyer_gstin");

    Map<String, Object> badDate = EinvoiceServiceAcTest.validInvoice();
    badDate.put("invoice_date", "not-a-date");
    assertField(badDate, "invoice_date");

    Map<String, Object> blankDate = EinvoiceServiceAcTest.validInvoice();
    blankDate.put("invoice_date", "");
    assertField(blankDate, "invoice_date");

    Map<String, Object> blankType = EinvoiceServiceAcTest.validInvoice();
    blankType.put("invoice_type", "");
    assertField(blankType, "invoice_type");

    Map<String, Object> emptyItems = EinvoiceServiceAcTest.validInvoice();
    emptyItems.put("items", List.of());
    assertField(emptyItems, "items");

    Map<String, Object> badItemsType = EinvoiceServiceAcTest.validInvoice();
    badItemsType.put("items", "nope");
    assertField(badItemsType, "items");

    Map<String, Object> nonObjectItem = EinvoiceServiceAcTest.validInvoice();
    List<Object> items = new ArrayList<>();
    items.add("bad");
    nonObjectItem.put("items", items);
    assertThatThrownBy(() -> InvoiceSchemaValidator.validate(nonObjectItem))
        .extracting(ex -> ((AppException) ex).details().get("field").toString())
        .asString()
        .startsWith("items[0]");

    Map<String, Object> missingQty = EinvoiceServiceAcTest.validInvoice();
    @SuppressWarnings("unchecked")
    Map<String, Object> itemNoQty =
        new LinkedHashMap<>((Map<String, Object>) ((List<?>) missingQty.get("items")).get(0));
    itemNoQty.remove("qty");
    missingQty.put("items", List.of(itemNoQty));
    assertThatThrownBy(() -> InvoiceSchemaValidator.validate(missingQty))
        .extracting(ex -> ((AppException) ex).details().get("field"))
        .isEqualTo("items[0].qty");

    Map<String, Object> badPrice = EinvoiceServiceAcTest.validInvoice();
    @SuppressWarnings("unchecked")
    Map<String, Object> itemBadPrice =
        new LinkedHashMap<>((Map<String, Object>) ((List<?>) badPrice.get("items")).get(0));
    itemBadPrice.put("unit_price", "x");
    badPrice.put("items", List.of(itemBadPrice));
    assertThatThrownBy(() -> InvoiceSchemaValidator.validate(badPrice))
        .extracting(ex -> ((AppException) ex).details().get("field"))
        .isEqualTo("items[0].unit_price");

    Map<String, Object> badTax = EinvoiceServiceAcTest.validInvoice();
    badTax.put("tax_amounts", "x");
    assertField(badTax, "tax_amounts");

    Map<String, Object> incompleteTax = EinvoiceServiceAcTest.validInvoice();
    Map<String, Object> tax = new LinkedHashMap<>();
    tax.put("taxable_value", 1);
    incompleteTax.put("tax_amounts", tax);
    assertThatThrownBy(() -> InvoiceSchemaValidator.validate(incompleteTax))
        .extracting(ex -> ((AppException) ex).details().get("field"))
        .isEqualTo("tax_amounts.total_invoice_value");

    Map<String, Object> shortDate = EinvoiceServiceAcTest.validInvoice();
    shortDate.put("invoice_date", "2026-07");
    assertField(shortDate, "invoice_date");

    Map<String, Object> stringNumber = EinvoiceServiceAcTest.validInvoice();
    @SuppressWarnings("unchecked")
    Map<String, Object> itemStrNum =
        new LinkedHashMap<>((Map<String, Object>) ((List<?>) stringNumber.get("items")).get(0));
    itemStrNum.put("qty", "100");
    stringNumber.put("items", List.of(itemStrNum));
    InvoiceSchemaValidator.validate(stringNumber);
  }

  private static void assertField(Map<String, Object> data, String field) {
    assertThatThrownBy(() -> InvoiceSchemaValidator.validate(data))
        .satisfies(
            ex -> {
              AppException app = (AppException) ex;
              assertThat(app.code()).isEqualTo("INVALID_INVOICE_SCHEMA");
              assertThat(app.details()).containsEntry("field", field);
            });
  }
}
