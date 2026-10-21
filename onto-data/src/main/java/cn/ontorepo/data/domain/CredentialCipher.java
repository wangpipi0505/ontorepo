package cn.ontorepo.data.domain;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 数据源连接配置的 AES-256-GCM 加密边界。
 *
 * <p>主密钥必须由进程环境提供，不生成固定默认值；每次加密采用随机 nonce。 数据源稳定标识作为附加认证数据，防止把一个数据源的密文拷贝到另一行后获得访问能力。
 * 连接配置只在真实适配器调用前解密，管理 API 不返回明文。
 */
public final class CredentialCipher {
  private static final int MAX_CLEAR_BYTES = 65536;
  private static final SecureRandom RANDOM = new SecureRandom();
  private final SecretKeySpec key;

  public CredentialCipher(String base64Key) {
    try {
      byte[] bytes = Base64.getDecoder().decode(base64Key);
      if (bytes.length != 32) throw new IllegalArgumentException();
      key = new SecretKeySpec(bytes, "AES");
      Arrays.fill(bytes, (byte) 0);
    } catch (RuntimeException e) {
      throw new DataProblem("CREDENTIAL_KEY_INVALID", "数据源主密钥必须为 Base64 编码的 32 字节密钥");
    }
  }

  public String encrypt(String sourceId, String plaintext) {
    identity(sourceId);
    if (plaintext == null || plaintext.isEmpty())
      throw new DataProblem("CREDENTIAL_CONFIG_INVALID", "连接配置不能为空");
    byte[] clear = plaintext.getBytes(StandardCharsets.UTF_8);
    if (clear.length > MAX_CLEAR_BYTES)
      throw new DataProblem("CREDENTIAL_CONFIG_INVALID", "连接配置超过大小限制");
    byte[] nonce = new byte[12];
    RANDOM.nextBytes(nonce);
    try {
      byte[] encrypted = cipher(Cipher.ENCRYPT_MODE, nonce, sourceId).doFinal(clear);
      byte[] combined = new byte[nonce.length + encrypted.length];
      System.arraycopy(nonce, 0, combined, 0, nonce.length);
      System.arraycopy(encrypted, 0, combined, nonce.length, encrypted.length);
      return "v1." + Base64.getUrlEncoder().withoutPadding().encodeToString(combined);
    } catch (Exception e) {
      throw new DataProblem("CREDENTIAL_ENCRYPTION_FAILED", "连接配置加密失败");
    } finally {
      Arrays.fill(clear, (byte) 0);
    }
  }

  public String decrypt(String sourceId, String encoded) {
    identity(sourceId);
    try {
      if (encoded == null || !encoded.startsWith("v1.") || encoded.length() > 90000)
        throw new IllegalArgumentException();
      byte[] combined = Base64.getUrlDecoder().decode(encoded.substring(3));
      if (combined.length < 29 || combined.length > MAX_CLEAR_BYTES + 28)
        throw new IllegalArgumentException();
      byte[] nonce = Arrays.copyOfRange(combined, 0, 12);
      byte[] clear =
          cipher(Cipher.DECRYPT_MODE, nonce, sourceId).doFinal(combined, 12, combined.length - 12);
      try {
        return new String(clear, StandardCharsets.UTF_8);
      } finally {
        Arrays.fill(clear, (byte) 0);
      }
    } catch (Exception e) {
      throw new DataProblem("CREDENTIAL_INTEGRITY_FAILED", "连接配置密文无效或不属于此数据源");
    }
  }

  private Cipher cipher(int mode, byte[] nonce, String sourceId) throws Exception {
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(mode, key, new GCMParameterSpec(128, nonce));
    cipher.updateAAD(("ontorepo:datasource:v1:" + sourceId).getBytes(StandardCharsets.UTF_8));
    return cipher;
  }

  private static void identity(String sourceId) {
    if (sourceId == null || sourceId.isBlank() || sourceId.length() > 200)
      throw new DataProblem("CREDENTIAL_CONTEXT_INVALID", "数据源标识无效");
  }
}
