/**
 * [INPUT]: 接收按逗号或空白分隔的群管理员 ID。
 * [OUTPUT]: 提供区分大小写的 ID 集合与单个 ID 校验。
 * [POS]: 群配置和群内命令共用同一授权标识规则。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.text;

import java.util.LinkedHashSet;
import java.util.regex.Pattern;

public final class AdminIds {
    private static final Pattern SEPARATOR = Pattern.compile("[,\\s]+");
    private static final Pattern ID =
            Pattern.compile("[^,\\s\\p{C}]{1,160}", Pattern.UNICODE_CHARACTER_CLASS);

    private AdminIds() {}

    public static LinkedHashSet<String> parse(String encoded) {
        var ids = new LinkedHashSet<String>();
        if (encoded == null) {
            return ids;
        }
        for (String id : SEPARATOR.split(encoded)) {
            if (!id.isEmpty()) {
                ids.add(id);
            }
        }
        return ids;
    }

    public static boolean valid(String senderId) {
        return senderId != null && senderId.length() <= 160 && ID.matcher(senderId).matches();
    }
}
