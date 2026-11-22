package dev.qqbot.memory;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

public final class MemberLabels {
    private MemberLabels() {}

    public static Map<String, String> labels(UUID groupId, Collection<String> senders) {
        var hashes = new TreeMap<String, String>();
        for (String sender : senders) {
            hashes.put(sender, hash(groupId + ":" + sender));
        }
        var result = new LinkedHashMap<String, String>();
        for (var sender : hashes.entrySet()) {
            int width = 12;
            while (width < 64) {
                String prefix = sender.getValue().substring(0, width);
                if (hashes.entrySet().stream()
                        .noneMatch(
                                other ->
                                        !other.getKey().equals(sender.getKey())
                                                && other.getValue().startsWith(prefix))) {
                    break;
                }
                width++;
            }
            result.put(sender.getKey(), sender.getValue().substring(0, width));
        }
        return Collections.unmodifiableMap(result);
    }

    public static String hash(String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
