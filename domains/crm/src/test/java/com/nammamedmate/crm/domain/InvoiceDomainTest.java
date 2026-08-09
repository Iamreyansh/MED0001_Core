package com.nammamedmate.crm.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.kernel.error.AppException;
import org.junit.jupiter.api.Test;

class InvoiceDomainTest {

  @Test
  void statusesAndTypes() {
    assertThat(InvoiceStatus.requireValid("due")).isEqualTo(InvoiceStatus.DUE);
    assertThat(InvoiceStatus.isClosed(InvoiceStatus.PAID)).isTrue();
    assertThat(InvoiceStatus.isClosed(InvoiceStatus.WAIVED)).isTrue();
    assertThat(InvoiceStatus.countsAsOverdue(InvoiceStatus.OVERDUE)).isTrue();
    assertThat(InvoiceStatus.countsAsOverdue(InvoiceStatus.DUNNING)).isTrue();
    assertThatThrownBy(() -> InvoiceStatus.requireValid(null)).isInstanceOf(AppException.class);
    assertThatThrownBy(() -> InvoiceStatus.requireValid("")).isInstanceOf(AppException.class);
    assertThatThrownBy(() -> InvoiceStatus.requireValid("NOPE")).isInstanceOf(AppException.class);

    assertThat(InvoiceLineItemType.requireValid("plan")).isEqualTo(InvoiceLineItemType.PLAN);
    assertThatThrownBy(() -> InvoiceLineItemType.requireValid(null))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(() -> InvoiceLineItemType.requireValid(" "))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(() -> InvoiceLineItemType.requireValid("TAX"))
        .isInstanceOf(AppException.class);

    assertThat(SaasGst.gstPaise(0)).isZero();
    assertThat(SaasGst.gstPaise(100)).isEqualTo(18);
    assertThat(SaasGst.totalWithGstPaise(100)).isEqualTo(118);
    assertThat(SaasGst.SAC_CODE).isEqualTo("9983");
  }
}
