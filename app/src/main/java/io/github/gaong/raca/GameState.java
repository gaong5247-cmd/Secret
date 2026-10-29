package io.github.gaong.raca;

/** Screen-derived state only. No direct access to game world, ammo or health. */
public final class GameState {
    public final boolean candidate;
    public final double x, y, vx, vy, size, confidence, cameraMotion;
    public final int consecutiveFrames;
    public final long timeMs;

    public GameState(boolean candidate, double x, double y, double vx, double vy,
                     double size, double confidence, double cameraMotion,
                     int consecutiveFrames, long timeMs) {
        this.candidate = candidate;
        this.x = clamp(x, 0, 1);
        this.y = clamp(y, 0, 1);
        this.vx = clamp(vx, -1, 1);
        this.vy = clamp(vy, -1, 1);
        this.size = clamp(size, 0, 1);
        this.confidence = clamp(confidence, 0, 1);
        this.cameraMotion = clamp(cameraMotion, 0, 1);
        this.consecutiveFrames = Math.max(0, consecutiveFrames);
        this.timeMs = timeMs;
    }

    public static GameState missing(long timeMs) {
        return new GameState(false, .5, .5, 0, 0, 0, 0, 0, 0, timeMs);
    }

    public static double clamp(double x, double min, double max) {
        return Math.max(min, Math.min(max, x));
    }
}
