package com.nammamedmate.pharmacy.adapter.out.scan;

import com.nammamedmate.pharmacy.application.port.out.VirusScanner;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Stub virus scanner that rejects EICAR test strings (local/dev/IT only). Staging/prod use {@link
 * FailClosedVirusScanner} until ClamAV / GuardDuty Malware Protection is wired.
 */
@Component
@Profile("!prod & !staging")
public class LoggingVirusScanner implements VirusScanner {

  private static final Logger log = LoggerFactory.getLogger(LoggingVirusScanner.class);
  private static final String EICAR_SIGNATURE = "EICAR-STANDARD-ANTIVIRUS-TEST-FILE";

  @Override
  public void scan(byte[] content, String fileName) {
    // PII: never log original file names (often embed owner/doc ids)
    log.debug("Virus scan: size={}", content == null ? 0 : content.length);
    if (fileName != null && fileName.toLowerCase().contains("eicar")) {
      throw new VirusScanException("File name contains EICAR test string");
    }
    if (content != null) {
      String preview =
          new String(content, 0, Math.min(content.length, 256), StandardCharsets.UTF_8);
      if (preview.contains(EICAR_SIGNATURE)) {
        throw new VirusScanException("File content matches EICAR test signature");
      }
    }
  }
}
