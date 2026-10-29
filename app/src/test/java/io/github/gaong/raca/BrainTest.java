package io.github.gaong.raca;

import org.junit.Test;
import java.util.Random;
import static org.junit.Assert.*;

public class BrainTest {
    @Test public void unknownMotionNeverFiresOrMoves() {
        ClassicalBrain engine = new ClassicalBrain();
        OnlineLearner learner = new OnlineLearner(ClassicalBrain.Tactic.values().length, new Random(3));
        ClassicalBrain.Decision d = engine.decide(GameState.missing(1000), learner);
        assertFalse(d.fire);
        assertFalse(d.target);
        assertEquals(0, d.moveX, 0);
        assertEquals(0, learner.decisions());
    }

    @Test public void observedCandidateUsesFiniteGeometryScores() {
        ClassicalBrain engine = new ClassicalBrain();
        OnlineLearner learner = new OnlineLearner(ClassicalBrain.Tactic.values().length, new Random(3));
        for (int i = 0; i < 25; i++) {
            GameState state = new GameState(true, .48, .45, .04, -.02,
                    .14, .89, .06, i + 2, 1200 + i * 120);
            ClassicalBrain.Decision d = engine.decide(state, learner);
            assertTrue(d.target);
            assertTrue(Double.isFinite(d.score));
            assertTrue(Math.abs(d.aimX) <= .07);
            assertTrue(Math.abs(d.aimY) <= .055);
            assertTrue(Math.abs(d.moveX) <= 1);
            assertTrue(Math.abs(d.moveY) <= 1);
        }
        assertEquals(25, learner.decisions());
    }

    @Test public void deathPenaltyReducesRecentTacticValue() {
        OnlineLearner learner = new OnlineLearner(3, new Random(1));
        double[] features = new double[OnlineLearner.FEATURE_COUNT];
        features[3] = 1;
        learner.record(1, features);
        double before = learner.adjustment(1, features);
        learner.reward(-1);
        assertTrue(learner.adjustment(1, features) < before);
        assertEquals(1, learner.outcomes());
    }

    @Test public void positiveFeedbackImprovesRecentTacticValue() {
        OnlineLearner learner = new OnlineLearner(3, new Random(1));
        double[] features = new double[OnlineLearner.FEATURE_COUNT];
        features[2] = .9;
        learner.record(2, features);
        double before = learner.adjustment(2, features);
        learner.reward(1);
        assertTrue(learner.adjustment(2, features) > before);
    }

    @Test public void rewardLearningPersistsAndRejectsBadData() {
        OnlineLearner original = new OnlineLearner(3, new Random(1));
        double[] f = new double[OnlineLearner.FEATURE_COUNT];
        f[0] = 1;
        original.record(0, f);
        original.reward(2);
        OnlineLearner restored = new OnlineLearner(3, new Random(1));
        assertTrue(restored.restore(original.serialize()));
        assertEquals(original.adjustment(0, f), restored.adjustment(0, f), 1e-10);
        assertEquals(1, restored.outcomes());
        assertFalse(restored.restore("RACA1 3 0 0 Infinity"));
    }
}
