/**
 * [INPUT]: 提供 QQ Hello 帧和带序号的事件帧。
 * [OUTPUT]: 验证心跳间隔上下限和重放序号类型。
 * [POS]: 官方网关 JSON 解码测试。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
package dev.qqbot.channel;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OfficialQqGatewayProtocolTest {
    private final OfficialQqGatewayProtocol protocol =
            new OfficialQqGatewayProtocol(new ObjectMapper());

    @ParameterizedTest
    @ValueSource(longs = {100, 300_000})
    void heartbeatInterval_acceptsInclusiveBounds(long millis) throws Exception {
        var packet = protocol.decode("{\"d\":{\"heartbeat_interval\":" + millis + "}}");
        assertEquals(millis, OfficialQqGatewayProtocol.heartbeatInterval(packet));
    }

    @ParameterizedTest
    @ValueSource(longs = {99, 300_001})
    void heartbeatInterval_rejectsOutsideBounds(long millis) throws Exception {
        var packet = protocol.decode("{\"d\":{\"heartbeat_interval\":" + millis + "}}");
        assertThrows(
                IllegalStateException.class,
                () -> OfficialQqGatewayProtocol.heartbeatInterval(packet));
    }

    @Test
    void sequence_acceptsOnlyIntegralValues() throws Exception {
        assertEquals(
                Optional.of(5L), OfficialQqGatewayProtocol.sequence(protocol.decode("{\"s\":5}")));
        assertTrue(OfficialQqGatewayProtocol.sequence(protocol.decode("{\"s\":\"5\"}")).isEmpty());
        assertTrue(OfficialQqGatewayProtocol.sequence(protocol.decode("{}")).isEmpty());
    }
}
