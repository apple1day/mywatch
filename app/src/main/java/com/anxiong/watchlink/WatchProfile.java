package com.anxiong.watchlink;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/** Original interoperability helpers; no vendor code or credentials are embedded. */
public final class WatchProfile {
    private static final Pattern ORIGINAL_NAME = Pattern.compile("^ONEPLUS WATCH\\W+[0-9A-F]+$");
    public static final String SOURCE_SHA256 = "5b9ce81310236a3c02a603f4032ab9bc9ad482e95abcc1a654aeef4c31933ce1";
    public static final UUID COMMAND_UUID = UUID.fromString("db764ac8-4b08-7f25-aafe-59d03c27bae3");
    public static final UUID DATA_UUID = UUID.fromString("db764ac8-4b08-7f25-aafe-59d03c27bae4");
    private WatchProfile() {}

    public static boolean isNameCandidate(String name) {
        if (name == null) return false;
        String normalized = name.trim().toUpperCase(Locale.ROOT);
        return ORIGINAL_NAME.matcher(normalized).matches()
            || normalized.equals("ONEPLUS WATCH")
            || normalized.startsWith("W301CN") || normalized.startsWith("W501CN");
    }

    public static UUID standardUuid(String code) {
        return UUID.fromString("0000" + code + "-0000-1000-8000-00805f9b34fb");
    }

    public static String maskedAddress(String address) {
        if (address == null || address.length() < 5) return "未知地址";
        return "**:**:**:**:" + address.substring(address.length() - 5);
    }

    public static String anonymousId(String address, String sessionSalt) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                (sessionSalt + ":" + address).getBytes(StandardCharsets.UTF_8));
            return hex(digest).substring(0, 12);
        } catch (Exception e) { return "unavailable"; }
    }

    public static String hex(byte[] data) {
        if (data == null) return "";
        char[] out = new char[data.length * 2];
        final char[] digits = "0123456789ABCDEF".toCharArray();
        for (int i = 0; i < data.length; i++) {
            int b = data[i] & 255;
            out[i * 2] = digits[b >>> 4]; out[i * 2 + 1] = digits[b & 15];
        }
        return new String(out);
    }

    public static String standardLabel(UUID uuid) {
        if (uuid.equals(standardUuid("2a19"))) return "电量";
        if (uuid.equals(standardUuid("2a24"))) return "型号";
        if (uuid.equals(standardUuid("2a26"))) return "固件版本";
        if (uuid.equals(standardUuid("2a28"))) return "软件版本";
        if (uuid.equals(standardUuid("2a29"))) return "制造商";
        return uuid.toString();
    }

    public static String standardValue(UUID uuid, byte[] value) {
        if (value == null || value.length == 0) return "空值";
        if (uuid.equals(standardUuid("2a19"))) {
            if (value.length != 1 || (value[0] & 255) > 100) return "非有效标准电量值";
            return (value[0] & 255) + "%";
        }
        String text = new String(value, StandardCharsets.UTF_8).replace('\u0000', ' ').trim();
        return text.length() > 120 ? text.substring(0, 120) + "…" : text;
    }
}
