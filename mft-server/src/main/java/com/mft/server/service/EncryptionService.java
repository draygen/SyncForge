package com.mft.server.service;

import org.springframework.stereotype.Service;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class EncryptionService {

    private static final String AES = "AES";

    public String generateAesKey() throws NoSuchAlgorithmException {
        KeyGenerator keyGen = KeyGenerator.getInstance(AES);
        keyGen.init(256, new SecureRandom());
        SecretKey secretKey = keyGen.generateKey();
        return Base64.getEncoder().encodeToString(secretKey.getEncoded());
    }

    public byte[] encryptChunk(byte[] data, String base64Key) throws Exception {
        byte[] decodedKey = Base64.getDecoder().decode(base64Key);
        SecretKey originalKey = new SecretKeySpec(decodedKey, 0, decodedKey.length, AES);

        Cipher cipher = Cipher.getInstance(AES);
        cipher.init(Cipher.ENCRYPT_MODE, originalKey);
        return cipher.doFinal(data);
    }

    public byte[] decryptChunk(byte[] data, String base64Key) throws Exception {
        byte[] decodedKey = Base64.getDecoder().decode(base64Key);
        SecretKey originalKey = new SecretKeySpec(decodedKey, 0, decodedKey.length, AES);

        Cipher cipher = Cipher.getInstance(AES);
        cipher.init(Cipher.DECRYPT_MODE, originalKey);
        return cipher.doFinal(data);
    }

    // In a real application, you would encrypt this AES key with an RSA Public Key.
    // We are simulating that process here.
    public String encryptAesKeyWithMasterKey(String aesKey) {
        return "RSA_ENCRYPTED_[" + aesKey + "]";
    }

    public String decryptAesKeyWithMasterKey(String encryptedAesKey) {
        return encryptedAesKey.replace("RSA_ENCRYPTED_[", "").replace("]", "");
    }
}
