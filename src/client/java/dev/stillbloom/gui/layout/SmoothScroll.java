package dev.stillbloom.gui.layout;

public final class SmoothScroll {
    public static final long DURATION_NANOS = 100_000_000L;
    private double from, target, maximum;
    private long started;
    private boolean initialized;

    public void reset(double value, double max, long now) {
        maximum = limit(max);
        from = target = clamp(value);
        started = now;
        initialized = true;
    }

    public void bounds(double max, long now) {
        double current = value(now);
        maximum = limit(max);
        double next = clamp(target), visible = clamp(current);
        if (!initialized) {
            reset(0, maximum, now);
            return;
        }
        if (next != target || visible != current) {
            from = visible;
            target = next;
            started = now;
        }
    }

    public void wheel(double delta, double max, long now) {
        bounds(max, now);
        if (!Double.isFinite(delta)) return;
        double next = clamp(target + delta);
        if (next == target) return;
        from = value(now);
        target = next;
        started = now;
    }

    public double value(long now) {
        if (!initialized) return 0;
        double t = Math.clamp((now - started) / (double) DURATION_NANOS, 0, 1);
        double remain = 1 - t, eased = 1 - remain * remain * remain;
        return Math.clamp(from + (target - from) * eased, 0, maximum);
    }

    public int pixels(long now) {
        return (int) Math.round(value(now));
    }

    public double target() {
        return target;
    }

    public boolean moving(long now) {
        return initialized && from != target && now - started < DURATION_NANOS;
    }

    private double clamp(double value) {
        return Double.isFinite(value) ? Math.clamp(value, 0, maximum) : 0;
    }

    private static double limit(double value) {
        return Double.isFinite(value) ? Math.max(0, value) : 0;
    }
}
