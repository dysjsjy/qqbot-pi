package dev.qqbot.memory;

import java.util.regex.Pattern;

public final class QueryRouter {
    private static final Pattern HISTORY = Pattern.compile("之前|上次|以前|昨天|前天|上周|记得|谁说|什么时候|哪天");

    private QueryRouter() {}

    public static QueryMode route(String kind, String question) {
        return "MEMORY".equals(kind) || HISTORY.matcher(question).find()
                ? QueryMode.HISTORY
                : QueryMode.CHAT;
    }
}
