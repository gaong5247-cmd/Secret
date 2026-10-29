package io.github.gaong.raca;

import org.junit.Test;
import java.util.Random;
import static org.junit.Assert.*;

/** Sanity constraints for the build verification PR. */
public class DecisionInvariantTest {
    @Test public void uncertainMotionNeverTriggersCombat() {
        ClassicalBrain brain = new ClassicalBrain();
        OnlineLearner learner = new OnlineLearner(ClassicalBrain.Tactic.values().length, new Random(8));
        for (int i = 0; i < 50; i++) {
            GameState uncertain = new GameState(true, (i % 10) / 10., .55,
                    .8, .1, .3, .3, .2, 8, 1000 + i * 120);
            ClassicalBrain.Decision d = brain.decide(uncertain, learner);
            assertFalse(d.target);
            assertFalse(d.fire);
            assertFalse(d.knife);
        }
    }
    @Test public void malformedLearnedPolicyIsRejected() {
        OnlineLearner learner = new OnlineLearner(2, new Random(8));
        assertFalse(learner.restore("corrupted"));
        assertFalse(learner.restore("RACA1 2 0 0 0 1 2"));
    }
}
