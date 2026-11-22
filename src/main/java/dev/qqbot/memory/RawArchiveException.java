package dev.qqbot.memory;

public class RawArchiveException extends IllegalStateException {
    public RawArchiveException(String code) {
        super(code);
    }

    public RawArchiveException(String code, Throwable cause) {
        super(code, cause);
    }
}
