package io.github.gaong.raca;

/**
 * A deterministic, many-term classical evaluator with a small online residual.
 * Every observation is screen-derived; it is NOT a human-level controller.
 */
public final class ClassicalBrain {
    public enum Tactic {
        HOLD, STRAFE_LEFT, STRAFE_RIGHT, PUSH, RETREAT,
        CIRCLE_LEFT, CIRCLE_RIGHT, JUMP_PEEK, KNIFE_RUSH, TRACK
    }

    public static final class Decision {
        public final Tactic tactic;
        public final double moveX, moveY, aimX, aimY, score;
        public final boolean fire, jump, knife, target;
        Decision(Tactic tactic, double moveX, double moveY, double aimX, double aimY,
                 boolean fire, boolean jump, boolean knife, boolean target, double score) {
            this.tactic = tactic; this.moveX = moveX; this.moveY = moveY;
            this.aimX = aimX; this.aimY = aimY;
            this.fire = fire; this.jump = jump; this.knife = knife;
            this.target = target; this.score = score;
        }
        public static Decision idle() {
            return new Decision(Tactic.HOLD, 0, 0, 0, 0, false, false, false, false, 0);
        }
    }

    private static final double EPS = 1e-5;
    private Tactic previous = Tactic.HOLD;
    private long lastJumpMs = 0;

    public Decision decide(GameState state, OnlineLearner learning) {
        if (!state.candidate || state.consecutiveFrames < 2 || state.confidence < .5) {
            previous = Tactic.HOLD;
            return Decision.idle(); // fail closed: NEVER shoot at unknown motion
        }

        double x = state.x - .5;
        double y = state.y - .46;  // upper torso is an unverified approximation
        double horizon = GameState.clamp(.07 + .22 * (1 - state.size), .05, .29);
        double leadX = state.vx * horizon;
        double leadY = state.vy * horizon;
        double aimErrorX = GameState.clamp(x + leadX, -.5, .5);
        double aimErrorY = GameState.clamp(y + leadY, -.5, .5);
        double aimError = Math.hypot(aimErrorX, aimErrorY);
        double centered = 1.0 - GameState.clamp(aimError / .33, 0, 1);
        double near = GameState.clamp(state.size * 5.5, 0, 1);
        double mid = 1 - Math.abs(near - .45) * 2;
        double far = 1 - near;
        double targetLateralSpeed = GameState.clamp(Math.abs(state.vx) * 4, 0, 1);
        double targetVerticalSpeed = GameState.clamp(Math.abs(state.vy) * 4, 0, 1);
        double edgeRisk = GameState.clamp((Math.abs(x) - .3) * 5, 0, 1);
        double viewJitter = state.cameraMotion;
        double stability = 1 - viewJitter;
        double highConfidence = Math.max(0, (state.confidence - .5) * 2);
        double age = GameState.clamp(state.consecutiveFrames / 12.0, 0, 1);
        double bulletLine = centered * stability * highConfidence;
        double chaseLeft = (x < 0) ? 1 : -1;
        double escape = near * (1 - centered);
        double burstAdvantage = centered * highConfidence * (1 - targetLateralSpeed);
        double reactionThreat = near * (targetLateralSpeed + targetVerticalSpeed) * .5;
        double trackEase = (1 - targetLateralSpeed) * (1 - targetVerticalSpeed);
        double knifeWindow = GameState.clamp((near - .83) * 6, 0, 1);

        double[] f = new double[] {
            x * 2, y * 2, state.vx, state.vy, near, mid, far,
            centered, highConfidence, stability, edgeRisk, age,
            targetLateralSpeed, knifeWindow
        };

        double[] score = new double[Tactic.values().length];
        for (Tactic tactic : Tactic.values()) {
            double s = 0;
            // Each action is assessed by the same feature-rich, interpretable objective.
            s += .90 * highConfidence;
            s += .55 * age;
            s += .50 * mid;
            s += .28 * stability;
            s += .20 * trackEase;
            s += .28 * centered;
            s += .15 * burstAdvantage;
            s -= .42 * edgeRisk;
            s -= .25 * viewJitter;
            s -= .22 * reactionThreat;
            s -= .18 * Math.max(0, 1 - age);
            s -= .12 * targetVerticalSpeed;
            s += .14 * bulletLine;
            s -= .08 * escape;
            s += .16 * (1 - Math.abs(aimErrorY));
            s += .12 * (1 - Math.abs(aimErrorX));
            s += .06 * far;
            s -= .04 * near;

            switch (tactic) {
                case HOLD:
                    s += 1.60 * centered + .55 * burstAdvantage + .25 * stability;
                    s -= 1.15 * reactionThreat + .70 * (1 - centered);
                    break;
                case STRAFE_LEFT:
                    s += 1.45 * reactionThreat + .48 * mid + .44 * targetLateralSpeed;
                    s += .35 * (state.vx > 0 ? 1 : 0) + .18 * highConfidence;
                    s -= .50 * (x < -.35 ? 1 : 0) + .15 * far;
                    break;
                case STRAFE_RIGHT:
                    s += 1.45 * reactionThreat + .48 * mid + .44 * targetLateralSpeed;
                    s += .35 * (state.vx < 0 ? 1 : 0) + .18 * highConfidence;
                    s -= .50 * (x > .35 ? 1 : 0) + .15 * far;
                    break;
                case PUSH:
                    s += 1.45 * far + .55 * highConfidence + .35 * trackEase;
                    s += .35 * (1 - edgeRisk) + .17 * centered;
                    s -= 1.85 * near + .42 * reactionThreat;
                    break;
                case RETREAT:
                    s += 1.9 * near + .75 * reactionThreat + .35 * (1 - centered);
                    s -= 1.30 * far + .30 * stability;
                    break;
                case CIRCLE_LEFT:
                    s += 1.05 * mid + .75 * reactionThreat + .45 * targetLateralSpeed;
                    s += .20 * highConfidence + .14 * centered;
                    s -= .35 * edgeRisk + .35 * far;
                    break;
                case CIRCLE_RIGHT:
                    s += 1.05 * mid + .75 * reactionThreat + .45 * targetLateralSpeed;
                    s += .20 * highConfidence + .14 * centered;
                    s -= .35 * edgeRisk + .35 * far;
                    break;
                case JUMP_PEEK:
                    s += 1.2 * reactionThreat + .38 * mid + .22 * highConfidence;
                    s -= .9 * far + .4 * (1 - stability);
                    if (state.timeMs - lastJumpMs < 1400) s -= 9;
                    break;
                case KNIFE_RUSH:
                    s += 3.5 * knifeWindow + .45 * near + .25 * centered;
                    s -= 4.2 * (1 - knifeWindow) + .9 * reactionThreat;
                    break;
                case TRACK:
                    s += 1.35 * (1 - centered) + .25 * age + .25 * far;
                    s -= 1.00 * near * centered + .20 * viewJitter;
                    break;
            }
            if (tactic == previous) s += .25; // prevent rapid jitter
            score[tactic.ordinal()] = s;
        }

        int chosen = learning.choose(score, f);
        Tactic t = Tactic.values()[chosen];
        learning.record(chosen, f);
        previous = t;
        double mx = 0, my = 0;
        switch (t) {
            case STRAFE_LEFT: mx = -1; break;
            case STRAFE_RIGHT: mx = 1; break;
            case PUSH: my = 1; break;
            case RETREAT: my = -1; break;
            case CIRCLE_LEFT: mx = -.9; my = .45; break;
            case CIRCLE_RIGHT: mx = .9; my = .45; break;
            case JUMP_PEEK: mx = state.vx >= 0 ? -1 : 1; break;
            case KNIFE_RUSH: my = 1; break;
            case HOLD: break;
            case TRACK: mx = .32 * chaseLeft * -1; break;
        }
        boolean jump = t == Tactic.JUMP_PEEK;
        if (jump) lastJumpMs = state.timeMs;
        // Fire is gated by target geometry, age and confidence, not by arbitrary movement.
        boolean fire = (t != Tactic.KNIFE_RUSH)
            && state.confidence > .76 && age > .35 && centered > .74
            && aimError < .10 && viewJitter < .6;
        boolean knife = t == Tactic.KNIFE_RUSH && knifeWindow > .7
            && state.confidence > .8;
        // Gesture controller converts normalized angular corrections to screen drags.
        double aimX = GameState.clamp(aimErrorX * .21, -.07, .07);
        double aimY = GameState.clamp(aimErrorY * .18, -.055, .055);
        return new Decision(t, mx, my, aimX, aimY, fire,
                jump, knife, true, score[chosen]);
    }
}
