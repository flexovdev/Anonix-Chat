package me.flexov.anonymouschat.service;

import org.springframework.stereotype.Service;

@Service
public class EncryptionService {

    // Пример симметричного шифрования (AES). В реальном проекте ключи должны быть уникальны и не храниться на сервере.
    // Лучше шифровать на клиенте.
    public String encrypt(String plainText, String key) {
        // Заглушка: возвращаем тот же текст (в продакшене здесь должно быть шифрование)
        return plainText;
    }

    public String decrypt(String encryptedText, String key) {
        return encryptedText;
    }
}
