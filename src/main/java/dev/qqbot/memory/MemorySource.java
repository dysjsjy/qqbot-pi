package dev.qqbot.memory;

import java.time.Instant;

public record MemorySource(String id, String name, Instant time, String body, double score) {}
