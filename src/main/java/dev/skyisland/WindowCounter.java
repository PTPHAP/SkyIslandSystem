package dev.skyisland;

import java.util.ArrayDeque;
import java.util.Deque;

final class WindowCounter {
    private final Deque<Long> times = new ArrayDeque<>();

    int add(long now, long windowMillis) {
        expire(now, windowMillis);
        times.addLast(now);
        return times.size();
    }

    int count(long now, long windowMillis) {
        expire(now, windowMillis);
        return times.size();
    }

    private void expire(long now, long windowMillis) {
        while (!times.isEmpty() && times.peekFirst() < now - windowMillis) times.removeFirst();
    }
}
