package com.nammamedmate.security;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public final class RsaKeyLoader {

  private RsaKeyLoader() {}

  public static PrivateKey loadPrivateKeyPem(String pem) {
    try {
      byte[] decoded = decodePem(pem, "PRIVATE KEY");
      return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(decoded));
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid RSA private key PEM", e);
    }
  }

  public static PublicKey loadPublicKeyPem(String pem) {
    try {
      byte[] decoded = decodePem(pem, "PUBLIC KEY");
      return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(decoded));
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid RSA public key PEM", e);
    }
  }

  private static byte[] decodePem(String pem, String type) {
    String normalized =
        pem.replace("-----BEGIN " + type + "-----", "")
            .replace("-----END " + type + "-----", "")
            .replaceAll("\\s", "");
    return Base64.getDecoder().decode(normalized);
  }
}
