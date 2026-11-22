package dev.qqbot.memory;

import java.util.UUID;

public record RawPartition(UUID groupId, long epoch) {
    public RawPartition {
        if (groupId == null || epoch < 0) {
            throw new IllegalArgumentException("Invalid RAW partition");
        }
    }
}
