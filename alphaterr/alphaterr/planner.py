"""Time-bounded deterministic beam search + risk-aware classical evaluation.

Unlike chess, Territorial.io is continuous and partly simultaneous: a full
alpha-beta search is not appropriate. Branching uses sampled approximate events.
"""
from __future__ import annotations
from dataclasses import dataclass
from time import perf_counter
import math
from .sim import World, Move, WAIT, NEUTRAL

@dataclass
class Trust:
    """Only promote a model after automated *held-out* evaluation improves."""
    games: int = 0
    wins: int = 0
    baseline_wins: int = 0
    @property
    def neural_weight(self) -> float:
        if self.games < 40:
            return 0.
        delta = (self.wins - self.baseline_wins) / max(1, self.games)
        return min(.70, max(0., (delta - .02) * 2.5))

def classical_score(world: World, pid: int = 0) -> float:
    areas = world.areas()
    own = int(areas[pid])
    if not own:
        return -1e5
    if world.done:
        return 1e5 if world.winner == pid else -1e5
    total = max(1, world.land)
    own_bal = world.balance[pid]
    threat = sum(
        max(0., world.balance[e] - own_bal * .75) / max(1., own_bal)
        for e in world.neighbours(pid) if e >= 0
    )
    density = own_bal / max(1., own)
    # Expansion gains are balanced against interest, frontier and vulnerability.
    return (
        120. * own / total
        + 14. * math.log1p(own_bal) / math.log1p(total * 150.)
        + 7. * min(1., density / 100.)
        - 4.5 * threat
        - .08 * world.border(pid) / math.sqrt(own)
        + 3. * int(NEUTRAL in world.neighbours(pid))
    )

class Planner:
    def __init__(self, depth: int = 2, width: int = 7, budget_ms: int = 70,
                 neural_value=None, trust: Trust | None = None):
        self.depth = max(1, depth)
        self.width = max(1, width)
        self.budget_ms = max(1, budget_ms)
        self.neural_value = neural_value
        self.trust = trust or Trust()
        self.nodes = 0

    def _value(self, state: World, pid: int) -> float:
        classic = classical_score(state, pid)
        w = self.trust.neural_weight if self.neural_value is not None else 0.
        if w == 0:
            return classic
        # NNUE/CNN normalized training value in [-1,1], same rough score units.
        try:
            neural = float(self.neural_value(state, pid))
            if not math.isfinite(neural):
                return classic
            return classic * (1. - w) + 120. * max(-1., min(1., neural)) * w
        except (ValueError, RuntimeError, TypeError):
            return classic

    def choose(self, state: World, pid: int = 0) -> Move:
        deadline = perf_counter() + self.budget_ms / 1000.
        self.nodes = 0
        legal = state.legal(pid)
        if len(legal) == 1:
            return legal[0]
        root: list[tuple[float, World, Move]] = []
        cache: dict[bytes, float] = {}
        for action in legal:
            if perf_counter() >= deadline and root:
                break
            next_state = state.clone()
            next_state.step(action, pid)
            k = next_state.signature()
            v = cache.setdefault(k, self._value(next_state, pid))
            root.append((v, next_state, action))
            self.nodes += 1
        if not root:
            return WAIT
        root.sort(key=lambda x: x[0], reverse=True)
        best_move = root[0][2]
        beam = root[:self.width]
        for _ in range(1, self.depth):
            if perf_counter() >= deadline:
                break
            upcoming = []
            for _, position, first in beam:
                if position.done:
                    upcoming.append((self._value(position, pid), position, first))
                    continue
                for followup in position.legal(pid):
                    if perf_counter() >= deadline:
                        break
                    other = position.clone()
                    other.step(followup, pid)
                    key = other.signature()
                    v = cache.setdefault(key, self._value(other, pid))
                    upcoming.append((v, other, first))
                    self.nodes += 1
            if upcoming:
                upcoming.sort(key=lambda x: x[0], reverse=True)
                beam = upcoming[:self.width]
                best_move = beam[0][2]
        return best_move
