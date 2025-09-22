package com.app.time;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

@Component
public class TimeProvider {

    private final Clock clock;
    private final AtomicReference<LocalDateTime> lastTimestamp = new AtomicReference<>();

    public TimeProvider(Clock clock) {
        this.clock = clock;
    }

    public synchronized LocalDateTime now() {
        LocalDateTime current = LocalDateTime.now(clock);
        LocalDateTime lastValue = lastTimestamp.get();
        if (lastValue != null && !current.isAfter(lastValue)) {
            current = lastValue.plusNanos(1);
        }
        lastTimestamp.set(current);
        return current;
    }
}
