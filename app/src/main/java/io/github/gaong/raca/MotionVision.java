package io.github.gaong.raca;

import android.graphics.Bitmap;
import android.media.Image;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Unsupervised motion-blob candidate detector, NOT a trained person classifier.
 * Motion compensation handles small camera translations, not rotation/zoom.
 */
public final class MotionVision {
    public static final int W = 144, H = 81;
    private final int[] gray = new int[W * H];
    private final int[] previous = new int[W * H];
    private final int[] argb = new int[W * H];
    private final byte[] mask = new byte[W * H];
    private final boolean[] visited = new boolean[W * H];
    private final int[] queue = new int[W * H];

    private boolean initialized = false;
    private double lastX = .5, lastY = .5, lastVx = 0, lastVy = 0;
    private long lastTime = 0;
    private int streak = 0;
    private int components = 0;
    private int alignedDx = 0, alignedDy = 0;

    public synchronized GameState analyze(Image image, long nowMs) {
        if (image == null || image.getPlanes().length < 1) return GameState.missing(nowMs);
        Image.Plane p = image.getPlanes()[0];
        ByteBuffer bytes = p.getBuffer();
        int width = image.getWidth(), height = image.getHeight();
        int pixelStride = p.getPixelStride(), rowStride = p.getRowStride();
        if (pixelStride < 3 || width < W || height < H) return GameState.missing(nowMs);
        int limit = bytes.limit();
        for (int y = 0; y < H; y++) {
            int iy = Math.min(height - 1, (int) ((y + .5) * height / H));
            for (int x = 0; x < W; x++) {
                int ix = Math.min(width - 1, (int) ((x + .5) * width / W));
                int index = iy * rowStride + ix * pixelStride;
                int i = y * W + x;
                if (index + 2 >= limit) {
                    gray[i] = 0;
                    argb[i] = 0xff000000;
                    continue;
                }
                int r = bytes.get(index) & 255;
                int g = bytes.get(index + 1) & 255;
                int b = bytes.get(index + 2) & 255;
                gray[i] = (r * 77 + g * 150 + b * 29) >> 8;
                argb[i] = 0xff000000 | r << 16 | g << 8 | b;
            }
        }

        if (!initialized) {
            System.arraycopy(gray, 0, previous, 0, previous.length);
            lastTime = nowMs;
            initialized = true;
            return GameState.missing(nowMs);
        }

        // Optimize a global translation by robust low-resolution SAD.
        long bestError = Long.MAX_VALUE;
        int bestDx = 0, bestDy = 0;
        for (int dy = -3; dy <= 3; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                long error = 0;
                for (int y = 7; y < H - 7; y += 4) {
                    int sy = y + dy;
                    for (int x = 9; x < W - 9; x += 4) {
                        int sx = x + dx;
                        error += Math.abs(gray[y * W + x] - previous[sy * W + sx]);
                    }
                }
                if (error < bestError) {
                    bestError = error;
                    bestDx = dx;
                    bestDy = dy;
                }
            }
        }
        alignedDx = bestDx;
        alignedDy = bestDy;

        int count = 0;
        double diffSum = 0;
        for (int y = 6; y < H - 6; y += 4) {
            for (int x = 8; x < W - 8; x += 4) {
                int v = Math.abs(gray[y * W + x]
                        - previous[(y + bestDy) * W + x + bestDx]);
                diffSum += v;
                count++;
            }
        }
        double avgDelta = diffSum / Math.max(1, count);
        double cameraMotion = GameState.clamp(
                (Math.abs(bestDx) + Math.abs(bestDy)) / 12.0 + avgDelta / 150, 0, 1);
        int threshold = (int) GameState.clamp(17 + avgDelta * 1.5, 22, 62);
        Arrays.fill(mask, (byte) 0);
        Arrays.fill(visited, false);
        for (int y = 8; y < H - 8; y++) {
            for (int x = 8; x < W - 8; x++) {
                int pos = y * W + x;
                int shifted = (y + bestDy) * W + (x + bestDx);
                if (Math.abs(gray[pos] - previous[shifted]) > threshold)
                    mask[pos] = 1;
            }
        }

        components = 0;
        double best = -1e9, cxBest = 0, cyBest = 0, bestSize = 0, shapeBest = 0;
        for (int y = 8; y < H - 8; y++) {
            for (int x = 8; x < W - 8; x++) {
                int root = y * W + x;
                if (mask[root] == 0 || visited[root]) continue;
                int head = 0, tail = 0;
                queue[tail++] = root;
                visited[root] = true;
                int minX = x, maxX = x, minY = y, maxY = y, pixels = 0;
                while (head < tail) {
                    int u = queue[head++];
                    int ux = u % W, uy = u / W;
                    pixels++;
                    minX = Math.min(minX, ux); maxX = Math.max(maxX, ux);
                    minY = Math.min(minY, uy); maxY = Math.max(maxY, uy);
                    // 4-connected components: narrower and more predictable than 8-connected.
                    int[] neighbors = {u - 1, u + 1, u - W, u + W};
                    for (int v : neighbors) {
                        int vx = v % W, vy = v / W;
                        if (vx < 8 || vx >= W - 8 || vy < 8 || vy >= H - 8) continue;
                        if (!visited[v] && mask[v] != 0) {
                            visited[v] = true;
                            queue[tail++] = v;
                        }
                    }
                }
                components++;
                int bw = maxX - minX + 1, bh = maxY - minY + 1;
                if (pixels < 16 || bw < 4 || bh < 7 || bw > W * .50 || bh > H * .75)
                    continue;
                double aspect = (double) bh / bw;
                double filled = (double) pixels / (bw * bh);
                if (aspect < .62 || aspect > 4.3 || filled < .13 || filled > .94)
                    continue;
                double centerX = (minX + maxX) / (2.0 * W);
                double centerY = (minY + maxY) / (2.0 * H);
                double boxArea = (double) bw * bh / (W * H);
                double shape = 1 - GameState.clamp(Math.abs(Math.log(aspect / 1.65)) / 1.55, 0, 1);
                double persistence = 1 - GameState.clamp(
                        Math.hypot(centerX - lastX, centerY - lastY) * 3.5, 0, 1);
                double score = 1.2 * shape + .8 * persistence
                        + .30 * (1 - Math.abs(centerX - .5) * 2)
                        + .15 * Math.min(1, boxArea * 20)
                        - .35 * Math.abs(filled - .48);
                if (score > best) {
                    best = score; cxBest = centerX; cyBest = centerY;
                    bestSize = GameState.clamp(Math.sqrt(boxArea) * 2.6, 0, 1);
                    shapeBest = shape;
                }
            }
        }
        System.arraycopy(gray, 0, previous, 0, previous.length);

        long dt = Math.max(30, nowMs - lastTime);
        lastTime = nowMs;
        if (best < -100) {
            streak = 0;
            lastVx *= .4; lastVy *= .4;
            return GameState.missing(nowMs);
        }
        double drift = Math.hypot(cxBest - lastX, cyBest - lastY);
        boolean sameTrack = drift < .23;
        streak = sameTrack ? Math.min(streak + 1, 1000) : 1;
        double vx = sameTrack ? GameState.clamp((cxBest - lastX) * 1000 / dt, -1, 1) : 0;
        double vy = sameTrack ? GameState.clamp((cyBest - lastY) * 1000 / dt, -1, 1) : 0;
        lastVx = .52 * lastVx + .48 * vx;
        lastVy = .52 * lastVy + .48 * vy;
        lastX = cxBest; lastY = cyBest;
        // Confidence here measures motion-region shape/temporal consistency only.
        double conf = GameState.clamp(.34 + .24 * shapeBest
                + .18 * (1 - Math.abs(cxBest - .5))
                + .23 * Math.min(1, streak / 8.0) - .18 * cameraMotion, 0, .92);
        return new GameState(true, cxBest, cyBest, lastVx, lastVy, bestSize,
                conf, cameraMotion, streak, nowMs);
    }

    public synchronized Bitmap snapshot() {
        return Bitmap.createBitmap(argb, W, H, Bitmap.Config.ARGB_8888);
    }

    public synchronized String debug() {
        return "motion blobs " + components + " / compensated shift "
                + alignedDx + "," + alignedDy + " / streak " + streak;
    }
}
