package com.nammamedmate.pos.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.pos.domain.InvoiceSettings;
import com.nammamedmate.pos.domain.InvoiceTemplate;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JdbcInvoiceSettingsStoreTest {

  @Mock JdbcTemplate jdbc;
  JdbcInvoiceSettingsStore store;
  ObjectMapper mapper = new ObjectMapper();
  UUID pharmacy = UUID.randomUUID();
  Instant now = Instant.parse("2026-07-24T12:00:00Z");

  @BeforeEach
  void setUp() {
    store = new JdbcInvoiceSettingsStore(jdbc, mapper);
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
  }

  @Test
  void getOrCreateAndUpsert() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> rm = inv.getArgument(1);
              if (calls.getAndIncrement() == 0) {
                return List.of();
              }
              ResultSet rs = mockSettingsRs(null);
              return List.of(rm.mapRow(rs, 0));
            });
    InvoiceSettings created = store.getOrCreate(pharmacy);
    assertThat(created.invoicePrefix()).isEqualTo("INV");

    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> rm = inv.getArgument(1);
              return List.of(rm.mapRow(mockSettingsRs("{\"bank_name\":\"HDFC\"}"), 0));
            });
    assertThat(store.getOrCreate(pharmacy).bankDetails()).containsEntry("bank_name", "HDFC");

    InvoiceSettings upserted =
        store.upsert(
            new InvoiceSettings(
                pharmacy,
                InvoiceTemplate.THERMAL,
                "#000000",
                null,
                null,
                "Bill",
                "PHARM1",
                "Sign",
                Map.of("ifsc_code", "HDFC0001234"),
                null,
                null,
                true,
                true,
                true,
                false,
                now));
    assertThat(upserted.invoicePrefix()).isEqualTo("PHARM1");

    InvoiceSettings nullBank =
        store.upsert(
            new InvoiceSettings(
                pharmacy,
                InvoiceTemplate.MODERN,
                "#2563EB",
                null,
                null,
                "Tax Invoice",
                "INV",
                "Sign",
                null,
                null,
                null,
                true,
                true,
                true,
                false,
                now));
    assertThat(nullBank.bankDetails()).isNull();
  }

  @Test
  void defaultsWhenRaceAndBadJson() throws Exception {
    when(jdbc.query(anyString(), any(RowMapper.class), any())).thenReturn(List.of());
    InvoiceSettings defaults = store.getOrCreate(pharmacy);
    assertThat(defaults.template()).isEqualTo(InvoiceTemplate.MODERN);

    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> rm = inv.getArgument(1);
              return List.of(rm.mapRow(mockSettingsRs("{bad"), 0));
            });
    assertThat(store.getOrCreate(pharmacy).bankDetails()).isEmpty();

    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> rm = inv.getArgument(1);
              return List.of(rm.mapRow(mockSettingsRs("  "), 0));
            });
    assertThat(store.getOrCreate(pharmacy).bankDetails()).isNull();
  }

  @Test
  void writeJsonFailure() {
    ObjectMapper bad =
        new ObjectMapper() {
          @Override
          public String writeValueAsString(Object value) {
            throw new RuntimeException("boom");
          }
        };
    JdbcInvoiceSettingsStore broken = new JdbcInvoiceSettingsStore(jdbc, bad);
    assertThatThrownBy(
            () ->
                broken.upsert(
                    new InvoiceSettings(
                        pharmacy,
                        InvoiceTemplate.MODERN,
                        "#2563EB",
                        null,
                        null,
                        "Tax Invoice",
                        "INV",
                        "Sign",
                        Map.of("x", "y"),
                        null,
                        null,
                        true,
                        true,
                        true,
                        false,
                        now)))
        .isInstanceOf(IllegalStateException.class);
  }

  private ResultSet mockSettingsRs(String bankJson) throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("pharmacy_id")).thenReturn(pharmacy);
    when(rs.getString("template")).thenReturn("MODERN");
    when(rs.getString("accent_color")).thenReturn("#2563EB");
    when(rs.getString("logo_url")).thenReturn(null);
    when(rs.getString("signature_url")).thenReturn(null);
    when(rs.getString("document_title")).thenReturn("Tax Invoice");
    when(rs.getString("invoice_prefix")).thenReturn("INV");
    when(rs.getString("signatory_label")).thenReturn("Authorized Signatory");
    when(rs.getString("bank_details")).thenReturn(bankJson);
    when(rs.getString("terms_and_conditions")).thenReturn(null);
    when(rs.getString("footer_note")).thenReturn(null);
    when(rs.getBoolean("show_mrp_savings")).thenReturn(true);
    when(rs.getBoolean("show_doctor")).thenReturn(true);
    when(rs.getBoolean("show_hsn")).thenReturn(true);
    when(rs.getBoolean("print_bank_details")).thenReturn(false);
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));
    return rs;
  }
}
