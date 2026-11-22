/**
 * [INPUT]: 接收原生会话保存选项和可选的历史检查点。
 * [OUTPUT]: 表达一次调用是否使用持久会话。
 * [POS]: Pi 运行时的会话参数；检查点路径由文件边界校验。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.pi;

public record PiSession(boolean isPersistent, String previousCheckpoint) {
    /** 选择不写原生会话文件的运行模式。 */
    public static PiSession ephemeral() {
        return new PiSession(false, null);
    }
}
