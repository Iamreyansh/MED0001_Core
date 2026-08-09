package com.nammamedmate.crm.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.nammamedmate.crm.application.port.out.SaasInvoicePdfPort;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalSaasInvoicePdfAdapterTest {

  @TempDir Path temp;

  @Test
  void putAndSignedGet() throws Exception {
    LocalSaasInvoicePdfAdapter adapter = new LocalSaasInvoicePdfAdapter(temp);
    adapter.put("a/b.pdf", new byte[] {'%', 'P'});
    assertThat(Files.exists(temp.resolve("a-b.pdf"))).isTrue();
    SaasInvoicePdfPort.SignedUrl url = adapter.signedGet("a/b.pdf", Duration.ofHours(1));
    assertThat(url.url()).contains("file:");
    assertThat(url.expiresAt()).isNotNull();
    new LocalSaasInvoicePdfAdapter().put("x.pdf", new byte[] {1});
    assertThat(LocalSaasInvoicePdfAdapter.sanitize(null)).isEqualTo("unknown.pdf");
    assertThat(LocalSaasInvoicePdfAdapter.sanitize(" ")).isEqualTo("unknown.pdf");
  }

  @Test
  void putFailureWrapped() throws Exception {
    Path file = temp.resolve("not-a-dir");
    Files.writeString(file, "x");
    LocalSaasInvoicePdfAdapter adapter = new LocalSaasInvoicePdfAdapter(file);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> adapter.put("a.pdf", new byte[] {1}))
        .isInstanceOf(java.io.UncheckedIOException.class);
  }
}
