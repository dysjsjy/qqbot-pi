/**
 * [INPUT]: 接收群定时配置或运行快照中的模式与署名枚举值。
 * [OUTPUT]: 提供日报场景模式（CASUAL/KNOWLEDGE/WORK）与成员署名策略（KEY_ONLY/ANONYMOUS/ACTIVE）不可变配置。
 * [POS]: schedule 模块的业务配置值对象，贯穿读批、选材、写作与校验。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.schedule;

import java.util.Locale;
import java.util.Map;

public record SummaryProfile(StyleMode styleMode, SpeakerMode speakerMode) {
    public enum StyleMode {
        CASUAL,
        KNOWLEDGE,
        WORK
    }

    public enum SpeakerMode {
        KEY_ONLY,
        ANONYMOUS,
        ACTIVE
    }

    public SummaryProfile {
        styleMode = styleMode == null ? StyleMode.CASUAL : styleMode;
        speakerMode = speakerMode == null ? SpeakerMode.KEY_ONLY : speakerMode;
    }

    public static SummaryProfile defaults() {
        return new SummaryProfile(StyleMode.CASUAL, SpeakerMode.KEY_ONLY);
    }

    public static SummaryProfile of(String style, String speaker) {
        return new SummaryProfile(parseStyle(style), parseSpeaker(speaker));
    }

    public static SummaryProfile fromMap(Map<String, ?> map) {
        if (map == null) {
            return defaults();
        }
        Object style = map.containsKey("styleMode") ? map.get("styleMode") : map.get("style_mode");
        Object speaker =
                map.containsKey("speakerMode") ? map.get("speakerMode") : map.get("speaker_mode");
        try {
            return of(
                    style == null ? null : style.toString(),
                    speaker == null ? null : speaker.toString());
        } catch (IllegalArgumentException ignored) {
            return defaults();
        }
    }

    public static StyleMode parseStyle(String raw) {
        if (raw == null || raw.isBlank()) {
            return StyleMode.CASUAL;
        }
        return StyleMode.valueOf(raw.strip().toUpperCase(Locale.ROOT));
    }

    public static SpeakerMode parseSpeaker(String raw) {
        if (raw == null || raw.isBlank()) {
            return SpeakerMode.KEY_ONLY;
        }
        return SpeakerMode.valueOf(raw.strip().toUpperCase(Locale.ROOT));
    }
}
