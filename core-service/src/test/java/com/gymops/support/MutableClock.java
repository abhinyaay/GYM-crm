package com.gymops.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

/** Test clock that can be moved forward to exercise expiry, grace periods and scheduled transitions. */
public class MutableClock extends Clock {

    private final AtomicReference<Instant> offset = new AtomicReference<>(Instant.EPOCH);
    private final ZoneId zone;

    public MutableClock(ZoneId zone) {
        this.zone = zone;
    }

    public void advance(Duration duration) {
        offset.updateAndGet(o -> o.plus(duration));
    }

    public void reset() {
        offset.set(Instant.EPOCH);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return Clock.system(zone);
    }

    @Override
    public Instant instant() {
        return Instant.now().plusMillis(offset.get().toEpochMilli());
    }
}
