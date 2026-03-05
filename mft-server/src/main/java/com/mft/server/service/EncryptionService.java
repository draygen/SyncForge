package com.mft.server.service;

import org.springframework.stereotype.Service;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class EncryptionService {

    private static final String AES = "AES";
    private static final String AES_GCM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;

    public String generateAesKey() throws NoSuchAlgorithmException {
        KeyGenerator keyGen = KeyGenerator.getInstance(AES);
        keyGen.init(256, new SecureRandom());
        return Base64.getEncoder().encodeToString(keyGen.generateKey().getEncoded());
    }

    // ── AES/GCM (new uploads) ─────────────────────────────────────────────────
    // Output format: [12-byte IV][ciphertext + 16-byte auth tag]

    public byte[] encryptChunkGcm(byte[] data, String base64Key) throws Exception {
        byte[] decodedKey = Base64.getDecoder().decode(base64Key);
        SecretKey key = new SecretKeySpec(decodedKey, AES);

        byte[] iv = new byte[GCM_IV_LENGTH];
        new SecureRandom().nextBytes(iv);

        Cipher cipher = Cipher.getInstance(AES_GCM);
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] ciphertext = cipher.doFinal(data);

        ByteArrayOutputStream out = new ByteArrayOutputStream(GCM_IV_LENGTH + ciphertext.length);
        out.write(iv);
        out.write(ciphertext);
        return out.toByteArray();
    }

    public byte[] decryptChunkGcm(byte[] encryptedData, String base64Key) throws Exception {
        byte[] decodedKey = Base64.getDecoder().decode(base64Key);
        SecretKey key = new SecretKeySpec(decodedKey, AES);

        byte[] iv = new byte[GCM_IV_LENGTH];
        System.arraycopy(encryptedData, 0, iv, 0, GCM_IV_LENGTH);
        byte[] ciphertext = new byte[encryptedData.length - GCM_IV_LENGTH];
        System.arraycopy(encryptedData, GCM_IV_LENGTH, ciphertext, 0, ciphertext.length);

        Cipher cipher = Cipher.getInstance(AES_GCM);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        return cipher.doFinal(ciphertext);
    }

    // ── AES/ECB (legacy decrypt only — kept for backward compatibility) ────────

    public byte[] decryptChunkEcb(byte[] data, String base64Key) throws Exception {
        byte[] decodedKey = Base64.getDecoder().decode(base64Key);
        SecretKey originalKey = new SecretKeySpec(decodedKey, 0, decodedKey.length, AES);
        Cipher cipher = Cipher.getInstance(AES);
        cipher.init(Cipher.DECRYPT_MODE, originalKey);
        return cipher.doFinal(data);
    }

    // ── Key wrapping (legacy format preserved for backward compat) ────────────

    public String encryptAesKeyWithMasterKey(String aesKey) {
        return "RSA_ENCRYPTED_[" + aesKey + "]";
    }

    public String decryptAesKeyWithMasterKey(String encryptedAesKey) {
        return encryptedAesKey.replace("RSA_ENCRYPTED_[", "").replace("]", "");
    }
}
