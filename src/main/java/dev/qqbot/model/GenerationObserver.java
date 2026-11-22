package dev.qqbot.model;

public interface GenerationObserver {
    void dispatched();

    void usage(ModelUsage value);

    static GenerationObserver noop() {
        return new GenerationObserver() {
            public void dispatched() {}

            public void usage(ModelUsage value) {}
        };
    }
}
