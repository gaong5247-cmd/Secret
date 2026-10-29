package io.github.gaong.raca;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Lightweight contextual linear advantage learner.
 * No pretrained weights. Sparse outcome feedback updates short trace with discount.
 * Classical scores are preserved, learned offsets are capped to prevent collapse.
 */
public final class OnlineLearner {
    public static final int FEATURE_COUNT = 14;
    private static final int MAX_TRACE = 64;
    private static final double GAMMA = .94;
    private static final double ALPHA = .025;
    private static final double CLIP = 2.5;

    private final double[][] weights;
    private final double[] biases;
    private final ArrayDeque<Trace> trace = new ArrayDeque<>();
    private final Random random;
    private int outcomes = 0;
    private int decisions = 0;

    private static final class Trace {
        final int action;
        final double[] features;
        Trace(int action, double[] features) {
            this.action = action;
            this.features = features.clone();
        }
    }

    public OnlineLearner(int actions) { this(actions, new Random()); }

    OnlineLearner(int actions, Random random) {
        if (actions < 1) throw new IllegalArgumentException("actions");
        weights = new double[actions][FEATURE_COUNT];
        biases = new double[actions];
        this.random = random;
    }

    public synchronized double adjustment(int action, double[] features) {
        double v = biases[action];
        for (int i = 0; i < FEATURE_COUNT; i++) v += weights[action][i] * features[i];
        return GameState.clamp(v, -3, 3);
    }

    /** Explore only actions whose classical score is close to the best. */
    public synchronized int choose(double[] classical, double[] features) {
        if (classical.length != weights.length) throw new IllegalArgumentException("shape");
        double[] values = new double[classical.length];
        int best = 0;
        for (int i = 0; i < values.length; i++) {
            values[i] = classical[i] + adjustment(i, features);
            if (values[i] > values[best]) best = i;
        }
        double epsilon = Math.max(.018, .085 / Math.sqrt(1 + outcomes / 20.0));
        if (random.nextDouble() >= epsilon) return best;
        List<Integer> near = new ArrayList<>();
        for (int i = 0; i < classical.length; i++)
            if (values[i] >= values[best] - 1.1) near.add(i);
        return near.get(random.nextInt(near.size()));
    }

    public synchronized void record(int action, double[] features) {
        if (action < 0 || action >= weights.length || features.length != FEATURE_COUNT)
            throw new IllegalArgumentException("action/features");
        trace.addLast(new Trace(action, features));
        decisions++;
        while (trace.size() > MAX_TRACE) trace.removeFirst();
    }

    public synchronized void reward(double reward) {
        if (!Double.isFinite(reward)) return;
        reward = GameState.clamp(reward, -3, 3);
        if (trace.isEmpty()) return;
        double eligibility = 1.0;
        for (java.util.Iterator<Trace> it = trace.descendingIterator(); it.hasNext();) {
            Trace t = it.next();
            double step = ALPHA * eligibility * reward;
            biases[t.action] = GameState.clamp(biases[t.action] + step, -CLIP, CLIP);
            for (int i = 0; i < FEATURE_COUNT; i++) {
                weights[t.action][i] = GameState.clamp(
                        weights[t.action][i] + step * t.features[i] / FEATURE_COUNT,
                        -CLIP, CLIP);
            }
            eligibility *= GAMMA;
        }
        trace.clear();
        outcomes++;
    }

    public synchronized int outcomes() { return outcomes; }
    public synchronized int decisions() { return decisions; }
    public synchronized String serialize() {
        StringBuilder sb = new StringBuilder().append("RACA1 ").append(weights.length)
                .append(' ').append(outcomes).append(' ').append(decisions);
        for (int a = 0; a < weights.length; a++) {
            sb.append(' ').append(biases[a]);
            for (int f = 0; f < FEATURE_COUNT; f++) sb.append(' ').append(weights[a][f]);
        }
        return sb.toString();
    }

    public synchronized boolean restore(String saved) {
        if (saved == null || saved.isEmpty()) return false;
        try {
            String[] x = saved.split(" ");
            int n = Integer.parseInt(x[1]);
            if (!x[0].equals("RACA1") || n != weights.length
                || x.length != 4 + n * (FEATURE_COUNT + 1)) return false;
            double[] b = new double[n];
            double[][] w = new double[n][FEATURE_COUNT];
            int k = 4;
            for (int a = 0; a < n; a++) {
                b[a] = parseClamped(x[k++]);
                for (int i = 0; i < FEATURE_COUNT; i++) w[a][i] = parseClamped(x[k++]);
            }
            outcomes = Math.max(0, Integer.parseInt(x[2]));
            decisions = Math.max(0, Integer.parseInt(x[3]));
            for (int a = 0; a < n; a++) {
                biases[a] = b[a];
                System.arraycopy(w[a], 0, weights[a], 0, FEATURE_COUNT);
            }
            return true;
        } catch (RuntimeException ex) {
            return false; // malformed or incompatible saved policy -> reset
        }
    }

    private static double parseClamped(String raw) {
        double parsed = Double.parseDouble(raw);
        if (!Double.isFinite(parsed)) throw new NumberFormatException();
        return GameState.clamp(parsed, -CLIP, CLIP);
    }
}
