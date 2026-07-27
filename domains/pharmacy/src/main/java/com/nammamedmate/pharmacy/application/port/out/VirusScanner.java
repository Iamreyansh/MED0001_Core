package com.nammamedmate.pharmacy.application.port.out;

/** Port for virus scanning uploaded files. */
public interface VirusScanner {

  /**
   * Scan the given file bytes.
   *
   * @throws VirusScanException if the file is flagged as infected
   */
  void scan(byte[] content, String fileName);

  class VirusScanException extends RuntimeException {
    public VirusScanException(String message) {
      super(message);
    }
  }
}
