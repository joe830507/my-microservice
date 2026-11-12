package com.mymicroservice.notification.mail;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

// 信件主旨與內文的渲染。主旨固定、不含使用者輸入；內文只用簡單的 {{key}} 取代，不執行任何運算。
// 使用者可控的欄位（顧客名稱）最後才取代，避免名稱裡的 {{...}} 被後面的步驟再展開。
@Component
public class EmailTemplateRenderer {
    private static final Map<String, String> SUBJECTS = Map.of(
            "order-created", "訂單已成立",
            "order-paid", "付款成功",
            "order-shipped", "訂單已出貨",
            "order-cancelled", "訂單已取消",
            "payment-failed", "付款失敗");

    public String subject(String template) {
        String subject = SUBJECTS.get(template);
        if (subject == null) {
            throw new IllegalStateException("Unknown email template");
        }
        return subject;
    }

    public String body(String template, String displayName, UUID orderId, BigDecimal amount, String reason) {
        String cancelReason = "timeout".equals(reason) ? "逾時未付款" : "已取消";
        return load(template)
                .replace("{{orderId}}", String.valueOf(orderId))
                .replace("{{amount}}", amount.toPlainString())
                .replace("{{reason}}", cancelReason)
                .replace("{{name}}", sanitizeName(displayName));
    }

    private static String load(String template) {
        try (var input = new ClassPathResource("templates/mail/" + template + ".txt").getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to load email template", failure);
        }
    }

    // 移除控制字元並壓成單行，避免在純文字內文中偽造結構
    private static String sanitizeName(String name) {
        if (name == null || name.isBlank()) {
            return "顧客";
        }
        String sanitized = name.replaceAll("[\\p{Cc}\\p{Cf}]", " ").replaceAll("\\s+", " ").trim();
        return sanitized.isEmpty() ? "顧客" : sanitized;
    }
}
