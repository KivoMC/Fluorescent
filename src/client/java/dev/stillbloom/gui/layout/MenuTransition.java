package dev.stillbloom.gui.layout;

// I base the fade on elapsed time so it doesn't change speed with FPS.
public final class MenuTransition {
    public static final long OPEN_NANOS = 220_000_000L,
            CLOSE_NANOS = 175_000_000L,
            VIEW_NANOS = 120_000_000L;
    private long start, duration = OPEN_NANOS;
    private float from, to = 1;
    private boolean closing;

    public MenuTransition(long now) {
        start = now;
    }

    public float alpha(long now) {
        float t = (float) Math.clamp((now - start) / (double) Math.max(1, duration), 0, 1);
        float eased = t * t * (3 - 2 * t);
        return from + (to - from) * eased;
    }

    public void close(long now) {
        if (closing) return;
        from = alpha(now);
        to = 0;
        start = now;
        duration = CLOSE_NANOS;
        closing = true;
    }

    public void reopen(long now) {
        from = alpha(now);
        to = 1;
        start = now;
        duration = OPEN_NANOS;
        closing = false;
    }

    public void view(long now) {
        if (closing) return;
        from = Math.min(alpha(now), .68f);
        to = 1;
        start = now;
        duration = VIEW_NANOS;
    }

    public boolean closing() {
        return closing;
    }

    public boolean finished(long now) {
        return now - start >= duration;
    }
}
