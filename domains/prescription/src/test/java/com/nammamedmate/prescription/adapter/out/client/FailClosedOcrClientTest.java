package com.nammamedmate.prescription.adapter.out.client;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.kernel.error.AppException;
import org.junit.jupiter.api.Test;

class FailClosedOcrClientTest {

  @Test
  void refusesToInventExtract() {
    assertThatThrownBy(() -> new FailClosedOcrClient().extract(new byte[] {1}, "image/jpeg"))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("OCR_UNAVAILABLE");
  }
}
