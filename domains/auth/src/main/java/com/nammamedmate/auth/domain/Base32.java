package com.nammamedmate.auth.domain;

/** RFC 4648 Base32 (no padding) for TOTP secrets. */
public final class Base32 {

  private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
  private static final int[] DECODE = new int[128];

  static {
    java.util.Arrays.fill(DECODE, -1);
    for (int i = 0; i < ALPHABET.length; i++) {
      DECODE[ALPHABET[i]] = i;
      DECODE[Character.toLowerCase(ALPHABET[i])] = i;
    }
  }

  private Base32() {}

  public static String encode(byte[] data) {
    StringBuilder out = new StringBuilder((data.length * 8 + 4) / 5);
    int buffer = 0;
    int bitsLeft = 0;
    for (byte b : data) {
      buffer = (buffer << 8) | (b & 0xff);
      bitsLeft += 8;
      while (bitsLeft >= 5) {
        out.append(ALPHABET[(buffer >> (bitsLeft - 5)) & 31]);
        bitsLeft -= 5;
      }
    }
    if (bitsLeft > 0) {
      out.append(ALPHABET[(buffer << (5 - bitsLeft)) & 31]);
    }
    return out.toString();
  }

  public static byte[] decode(String encoded) {
    String cleaned = encoded.replace("=", "").replace(" ", "");
    int length = cleaned.length();
    byte[] out = new byte[length * 5 / 8];
    int buffer = 0;
    int bitsLeft = 0;
    int index = 0;
    for (int i = 0; i < length; i++) {
      char c = cleaned.charAt(i);
      if (c >= DECODE.length || DECODE[c] < 0) {
        throw new IllegalArgumentException("Invalid Base32 character: " + c);
      }
      buffer = (buffer << 5) | DECODE[c];
      bitsLeft += 5;
      if (bitsLeft >= 8) {
        out[index++] = (byte) ((buffer >> (bitsLeft - 8)) & 0xff);
        bitsLeft -= 8;
      }
    }
    return out;
  }
}
