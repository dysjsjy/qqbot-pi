package dev.qqbot.text;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TextsTest {
    @Test
    void stripsPresentationSyntaxButKeepsReadableContent() {
        String text =
                "# 答复\n**重点** 和 *提醒*，`value = 42` 🙂 [S1]\n- 访问 [文档](https://example.com/a)\n```java\nSystem.out.println(42);\n```";
        assertEquals(
                "答复\n重点 和 提醒，value = 42 🙂 [S1]\n• 访问 文档 (https://example.com/a)\nSystem.out.println(42);",
                Texts.plainText(text));
    }

    @Test
    void fencedAndInlineCodePreserveLiteralMarkdownAndIndentation() {
        String code = "String value = \"**literal**\";\n    // _unchanged_ | value";
        assertEquals(code, Texts.plainText("```java\n" + code + "\n```"));
        assertEquals(
                "示例 **literal** 与 snake_case，提醒",
                Texts.plainText("示例 `**literal**` 与 snake_case，_提醒_"));
        assertEquals(
                "项目 · 状态\n发布 · 完成", Texts.plainText("| 项目 | 状态 |\n| --- | --- |\n| 发布 | **完成** |"));
        assertEquals(code, String.join("", Texts.delivery("```java\n" + code + "\n```")));
        assertEquals("    return 42", Texts.plainText("```python\n    return 42\n```"));
    }

    @Test
    void removesPrivateBlocksAndKnownDiagnosticsWithoutDeletingNormalLogDiscussion() {
        String text =
                "<think>私密推理\n不发送</think>正文\n2026-11-10T12:30:00.123+08:00 INFO 123 --- AGENT_START\n[DEBUG] internal secret\nAGENT_START run=secret group=private\n{\"type\":\"message_update\",\"secret\":\"no\"}\nINFO 是日志级别，log 文件可在管理端查看。\n<thinking>未闭合的思考";
        assertEquals("正文\nINFO 是日志级别，log 文件可在管理端查看。", Texts.plainText(text));
        assertTrue(Texts.delivery("<analysis>私密</analysis>\n[INFO] internal").isEmpty());
    }

    @Test
    void summaryDisplayDropsInternalReferencesOnlyAndDeliveryStillHonorsUnicodeLimits() {
        String report =
                "## 昨日结论\n发布通过 [01234567-89ab-cdef-0123-456789abcdef][01234567-89ab-cdef-0123-456789abcdef]\n版本 [v2] 与 [S1] 保留";
        assertEquals(
                "昨日结论\n发布通过\n版本 [v2] 与 [S1] 保留\n完整报告与来源可在管理端查看。", Texts.summaryDelivery(report));
        var parts = Texts.delivery("**" + "🙂".repeat(1700) + "**");
        assertEquals(3, parts.size());
        assertTrue(parts.stream().allMatch(part -> part.codePointCount(0, part.length()) <= 500));
        assertTrue(parts.get(2).endsWith("（展示已截断，完整报告请在管理端查看）"));
    }
}
