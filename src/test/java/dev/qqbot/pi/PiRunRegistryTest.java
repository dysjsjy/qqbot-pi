/**
 * [INPUT]: 使用无子进程的合成运行及固定槽位争用。
 * [OUTPUT]: 验证重复运行拒绝与部分占用后的槽位回收。
 * [POS]: Pi 并发登记测试；不等待真实执行槽超时。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.pi;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class PiRunRegistryTest {
    @Test
    void register_rejectsDuplicateWithoutReplacingCurrentRun() {
        var registry = new PiRunRegistry();
        UUID id = UUID.randomUUID();
        var original = registry.register(id);

        var failure = assertThrows(IllegalStateException.class, () -> registry.register(id));

        assertEquals("PI_RUN_ACTIVE", failure.getMessage());
        assertSame(original, registry.find(id).orElseThrow());
    }

    @Test
    void release_returnsBackgroundSlotAfterTotalSlotAcquisitionFails() throws Exception {
        var registry = new PiRunRegistry();
        var first = registry.register(UUID.randomUUID());
        var second = registry.register(UUID.randomUUID());
        var blocked = registry.register(UUID.randomUUID());
        registry.acquire(first, false, 0);
        registry.acquire(second, false, 0);

        var failure =
                assertThrows(IllegalStateException.class, () -> registry.acquire(blocked, true, 0));

        assertEquals("BACKGROUND_BUSY", failure.getMessage());
        registry.release(blocked);
        registry.release(first);
        var next = registry.register(UUID.randomUUID());
        assertDoesNotThrow(() -> registry.acquire(next, true, 0));
    }
}
