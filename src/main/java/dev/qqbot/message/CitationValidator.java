package dev.qqbot.message;

import dev.qqbot.memory.CitationMode;
import java.util.regex.Pattern;

public final class CitationValidator {
    private static final Pattern CITATIONS = Pattern.compile("\\[S([0-9]+)\\]");

    private CitationValidator() {}

    public static boolean accepts(String answer, CitationMode mode, int sources) {
        if (mode == CitationMode.NONE) {
            return true;
        }
        var matcher = CITATIONS.matcher(answer);
        boolean cited = false;
        while (matcher.find()) {
            int value = 0;
            for (int offset = matcher.start(1); offset < matcher.end(1); offset++) {
                int digit = answer.charAt(offset) - '0';
                if (value > sources / 10 || (value == sources / 10 && digit > sources % 10)) {
                    return false;
                }
                value = value * 10 + digit;
            }
            if (value < 1) {
                return false;
            }
            cited = true;
        }
        return mode != CitationMode.REQUIRED || cited;
    }
}
