package com.nammamedmate.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** AES-256-GCM encrypt/decrypt; ciphertext = Base64(IV || ciphertext || tag). */
public final class AesGcmCipher {

  private static final String TRANSFORMATION = "AES/GCM/NoPadding";
  private static final int IV_LENGTH = 12;
  private static final int TAG_BITS = 128;
  private static final int KEY_LENGTH = 32;

  @FunctionalInterface
  interface CipherFactory {
    Cipher create() throws GeneralSecurityException;
  }

  private final SecretKey key;
  private final SecureRandom secureRandom;
  private final CipherFactory cipherFactory;

  public AesGcmCipher(byte[] keyBytes) {
    this(keyBytes, new SecureRandom());
  }

  public AesGcmCipher(byte[] keyBytes, SecureRandom secureRandom) {
    this(keyBytes, secureRandom, () -> Cipher.getInstance(TRANSFORMATION));
  }

  AesGcmCipher(byte[] keyBytes, SecureRandom secureRandom, CipherFactory cipherFactory) {
    Objects.requireNonNull(keyBytes, "keyBytes");
    if (keyBytes.length != KEY_LENGTH) {
      throw new IllegalArgumentException("AES-256 key must be 32 bytes");
    }
    this.key = new SecretKeySpec(Arrays.copyOf(keyBytes, KEY_LENGTH), "AES");
    this.secureRandom = Objects.requireNonNull(secureRandom);
    this.cipherFactory = Objects.requireNonNull(cipherFactory);
  }

  public static AesGcmCipher fromBase64Key(String base64Key) {
    return new AesGcmCipher(Base64.getDecoder().decode(base64Key));
  }

  public String encrypt(String plaintext) {
    Objects.requireNonNull(plaintext, "plaintext");
    try {
      byte[] iv = new byte[IV_LENGTH];
      secureRandom.nextBytes(iv);
      Cipher cipher = cipherFactory.create();
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
      byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      ByteBuffer buffer = ByteBuffer.allocate(iv.length + ciphertext.length);
      buffer.put(iv);
      buffer.put(ciphertext);
      return Base64.getEncoder().encodeToString(buffer.array());
    } catch (GeneralSecurityException ex) {
      throw new IllegalStateException("AES-GCM encrypt failed", ex);
    }
  }

  public String decrypt(String encoded) {
    Objects.requireNonNull(encoded, "encoded");
    try {
      byte[] all = Base64.getDecoder().decode(encoded);
      if (all.length <= IV_LENGTH) {
        throw new IllegalArgumentException("ciphertext too short");
      }
      byte[] iv = Arrays.copyOfRange(all, 0, IV_LENGTH);
      byte[] ciphertext = Arrays.copyOfRange(all, IV_LENGTH, all.length);
      Cipher cipher = cipherFactory.create();
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
      byte[] plain = cipher.doFinal(ciphertext);
      return new String(plain, StandardCharsets.UTF_8);
    } catch (GeneralSecurityException | IllegalArgumentException ex) {
      throw new IllegalStateException("AES-GCM decrypt failed", ex);
    }
  }
}
