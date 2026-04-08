package com.aicomm.util;

/**
 * Masks sensitive data for safe logging.
 * Never log full phone numbers, contact IDs, or full names.
 */
public final class MaskingUtil {

    private MaskingUtil() {}

    /**
     * Masks phone number: +79817110577 → +7981***0577
     */
    public static String maskPhone(String phone) {
        if (phone == null || phone.length() < 6) return "***";
        return phone.substring(0, 4) + "***" + phone.substring(phone.length() - 4);
    }

    /**
     * Masks contact ID: 491865728 → 4918***728
     */
    public static String maskContactId(String contactId) {
        if (contactId == null || contactId.length() < 6) return "***";
        return contactId.substring(0, 4) + "***" + contactId.substring(contactId.length() - 3);
    }

    /**
     * Masks full name: Viktor Vikulitko → V. V***
     */
    public static String maskName(String name) {
        if (name == null || name.isBlank()) return "***";
        var parts = name.trim().split("\\s+");
        if (parts.length == 1) {
            return parts[0].charAt(0) + "***";
        }
        return parts[0].charAt(0) + ". " + parts[1].charAt(0) + "***";
    }

    /**
     * Truncates text for safe logging: "long text..." → "long te..."
     */
    public static String truncate(String text, int maxLen) {
        if (text == null) return "<null>";
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }

    /**
     * Masks Telegram chat ID: 491865728 → ***5728
     */
    public static String maskChatId(long chatId) {
        var str = String.valueOf(chatId);
        if (str.length() < 4) return "***";
        return "***" + str.substring(str.length() - 4);
    }
}
