package dev.qqbot.persistence;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class DatabaseValuesTest {
    @Test
    void localValuesRoundTripWithoutChangingBodies() {
        var values = new DatabaseValues(new ObjectMapper());
        Instant time = Instant.parse("2026-11-10T12:00:00Z");
        UUID id = UUID.randomUUID();
        assertArrayEquals(
                new Object[] {id.toString(), time.toEpochMilli(), 1, "09:00"},
                values.bind(new Object[] {id, time, true, LocalTime.of(9, 0)}));
        assertEquals(id, values.decode("group_id", id.toString()));
        assertEquals(Timestamp.from(time), values.decode("occurred_at", time.toEpochMilli()));
        assertEquals(true, values.decode("enabled", 1));
        assertEquals(2L, values.decode("epoch", 2));
        assertEquals("unchanged body", values.decode("body", "unchanged body"));
        assertEquals(List.of("source"), values.decode("source_ids", "[\"source\"]"));
    }
}
