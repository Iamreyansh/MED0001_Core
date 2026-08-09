package com.nammamedmate.inventory.adapter.out.export;

import com.nammamedmate.inventory.application.port.out.InventoryExcelExporter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Minimal Office Open XML (.xlsx) writer — no Apache POI dependency.
 *
 * <p>ponytail: enough for pharmacy inventory exports; upgrade to POI if formulas/styles needed.
 */
public final class SimpleXlsxExporter implements InventoryExcelExporter {

  private static final String[] HEADERS = {
    "id",
    "name",
    "manufacturer",
    "salt_composition",
    "form",
    "pack_size",
    "pack_unit",
    "mrp",
    "total_stock_units",
    "reorder_level",
    "earliest_expiry",
    "is_rx_only",
    "is_online_visible",
    "cost_value",
    "mrp_value",
    "flags"
  };

  @Override
  public byte[] export(List<Map<String, Object>> products) {
    return exportSheet("Inventory", HEADERS, products);
  }

  public byte[] exportSheet(String sheetName, String[] headers, List<Map<String, Object>> rows) {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    writeXlsx(bos, sheetName == null ? "Sheet1" : sheetName, headers, rows);
    return bos.toByteArray();
  }

  /** Package-visible for failure-path tests. */
  static void writeXlsx(
      OutputStream out, String sheetName, String[] headers, List<Map<String, Object>> products) {
    try (ZipOutputStream zip = new ZipOutputStream(out)) {
      write(zip, "[Content_Types].xml", contentTypes());
      write(zip, "_rels/.rels", rootRels());
      write(zip, "xl/workbook.xml", workbook(sheetName));
      write(zip, "xl/_rels/workbook.xml.rels", workbookRels());
      write(zip, "xl/worksheets/sheet1.xml", sheet(headers, products));
      write(zip, "xl/sharedStrings.xml", sharedStrings(headers, products));
      write(zip, "docProps/core.xml", coreProps());
      write(zip, "docProps/app.xml", appProps());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void write(ZipOutputStream zip, String name, String xml) throws IOException {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(xml.getBytes(StandardCharsets.UTF_8));
    zip.closeEntry();
  }

  private static String contentTypes() {
    return """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
          <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
          <Default Extension="xml" ContentType="application/xml"/>
          <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
          <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
          <Override PartName="/xl/sharedStrings.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml"/>
          <Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
          <Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>
        </Types>
        """;
  }

  private static String rootRels() {
    return """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
          <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
          <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>
        </Relationships>
        """;
  }

  private static String workbook(String sheetName) {
    return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
        + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\""
        + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
        + "<sheets><sheet name=\""
        + xmlEscape(sheetName)
        + "\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>";
  }

  private static String workbookRels() {
    return """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
          <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings" Target="sharedStrings.xml"/>
        </Relationships>
        """;
  }

  private static String coreProps() {
    return """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties"
           xmlns:dc="http://purl.org/dc/elements/1.1/"
           xmlns:dcterms="http://purl.org/dc/terms/"
           xmlns:dcmitype="http://purl.org/dc/dcmitype/"
           xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
          <dc:title>Inventory Export</dc:title>
          <dc:creator>Namma MedMate</dc:creator>
        </cp:coreProperties>
        """;
  }

  private static String appProps() {
    return """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties">
          <Application>Namma MedMate</Application>
        </Properties>
        """;
  }

  private static String sharedStrings(String[] headers, List<Map<String, Object>> products) {
    StringBuilder sb = new StringBuilder();
    List<String> strings = collectStrings(headers, products);
    sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
    sb.append("<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" count=\"")
        .append(strings.size())
        .append("\" uniqueCount=\"")
        .append(strings.size())
        .append("\">");
    for (String s : strings) {
      sb.append("<si><t>").append(xmlEscape(s)).append("</t></si>");
    }
    sb.append("</sst>");
    return sb.toString();
  }

  private static List<String> collectStrings(String[] headers, List<Map<String, Object>> products) {
    ArrayList<String> out = new ArrayList<>();
    for (String h : headers) {
      out.add(h);
    }
    if (products != null) {
      for (Map<String, Object> row : products) {
        for (String h : headers) {
          out.add(cellString(row.get(h)));
        }
      }
    }
    return out;
  }

  private static String sheet(String[] headers, List<Map<String, Object>> products) {
    StringBuilder sb = new StringBuilder();
    sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
    sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
    sb.append("<sheetData>");
    int rows = 1 + (products == null ? 0 : products.size());
    int idx = 0;
    for (int r = 0; r < rows; r++) {
      sb.append("<row r=\"").append(r + 1).append("\">");
      for (int c = 0; c < headers.length; c++) {
        String ref = colName(c) + (r + 1);
        sb.append("<c r=\"").append(ref).append("\" t=\"s\"><v>").append(idx++).append("</v></c>");
      }
      sb.append("</row>");
    }
    sb.append("</sheetData></worksheet>");
    return sb.toString();
  }

  private static String colName(int index) {
    return Character.toString((char) ('A' + index));
  }

  private static String cellString(Object value) {
    if (value == null) {
      return "";
    }
    if (value instanceof List<?> list) {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < list.size(); i++) {
        if (i > 0) {
          sb.append('|');
        }
        sb.append(list.get(i) == null ? "" : list.get(i).toString());
      }
      return sb.toString();
    }
    return value.toString();
  }

  static String xmlEscape(String s) {
    if (s == null || s.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder(s.length() + 8);
    for (int i = 0; i < s.length(); i++) {
      char ch = s.charAt(i);
      switch (ch) {
        case '&' -> sb.append("&amp;");
        case '<' -> sb.append("&lt;");
        case '>' -> sb.append("&gt;");
        case '"' -> sb.append("&quot;");
        default -> {
          if (ch < 0x20 && ch != '\t' && ch != '\n' && ch != '\r') {
            // skip control chars
          } else {
            sb.append(ch);
          }
        }
      }
    }
    return sb.toString();
  }

  public static boolean looksLikeXlsx(byte[] bytes) {
    return bytes != null && bytes.length > 3 && bytes[0] == 'P' && bytes[1] == 'K';
  }
}
