package dev.fallingcloud.dynamicelytra.trail;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The rolling point history for one wing. Bounded so memory is trivially capped; expired points fall off the
 * tail once they pass the configured lifetime.
 */
public final class RibbonHistory {
    private static final int MAX_POINTS = 220;

    private final Deque<TrailPoint> points = new ArrayDeque<>();

    public void push(TrailPoint p) {
        points.addLast(p);
        while (points.size() > MAX_POINTS) {
            points.removeFirst();
        }
    }

    /** Drop points older than {@code lifetime} ticks. */
    public void age(long now, int lifetime) {
        while (!points.isEmpty() && now - points.peekFirst().birthTick() > lifetime) {
            points.removeFirst();
        }
    }

    public boolean isEmpty() {
        return points.isEmpty();
    }

    public int size() {
        return points.size();
    }

    /** Oldest → newest. */
    public Iterable<TrailPoint> points() {
        return points;
    }
}
