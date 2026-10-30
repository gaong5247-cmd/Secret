"""A deliberately *approximate* reproducible territory simulator.

The real Territorial.io physics and UI may change. Never claim a simulator win
equals winning the real game. Land income / interest / defensive advantage are
calibratable priors, not reverse-engineered game code.
"""
from __future__ import annotations
from dataclasses import dataclass
import copy
import numpy as np

NEUTRAL = -1
SEA = -2
PCTS = (0.06, 0.10, 0.18, 0.30, 0.45)

@dataclass(frozen=True)
class Move:
    target: int   # -1 neutral, 0..n_players-1 owner; -3 means WAIT
    fraction: float = 0.0
    def __str__(self):
        return "WAIT" if self.target == -3 else f"{self.target}:{self.fraction:.2f}"

WAIT = Move(-3, 0.0)

class World:
    """Actions advance one discrete tick. Bots act at every tick.

    Fixed action indexing, deterministic seed and array observations keep this
    env suitable for policy-gradient learning and regression tests.
    """
    def __init__(self, size: int = 18, players: int = 5, seed: int = 0):
        if size < 10 or players < 2 or players > 8:
            raise ValueError("size >=10 and 2<=players<=8 required")
        self.size = size
        self.n = players
        self.seed = seed
        self.rng = np.random.default_rng(seed)
        self.owner = np.full((size, size), NEUTRAL, dtype=np.int8)
        # Border water and scattered inland obstacles.
        self.owner[0, :] = SEA
        self.owner[-1, :] = SEA
        self.owner[:, 0] = SEA
        self.owner[:, -1] = SEA
        noise = self.rng.random((size, size)) < .045
        self.owner[noise] = SEA
        available = np.argwhere(self.owner == NEUTRAL)
        seeds = self.rng.choice(len(available), size=players, replace=False)
        for p, index in enumerate(seeds):
            y, x = available[index]
            self.owner[y, x] = p
        self.balance = np.full(players, 90.0, dtype=np.float64)
        self.tick = 0
        self.done = False
        self.winner: int | None = None
        self.last_reward = np.zeros(players, dtype=np.float64)

    def clone(self) -> "World":
        return copy.deepcopy(self)

    @property
    def land(self) -> int:
        return int(np.count_nonzero(self.owner != SEA))

    def areas(self) -> np.ndarray:
        return np.bincount(self.owner[self.owner >= 0].astype(int), minlength=self.n)

    def neighbours(self, pid: int) -> set[int]:
        m = self.owner
        owned = m == pid
        border = np.zeros_like(owned)
        border[1:, :] |= owned[:-1, :]
        border[:-1, :] |= owned[1:, :]
        border[:, 1:] |= owned[:, :-1]
        border[:, :-1] |= owned[:, 1:]
        return {int(q) for q in np.unique(m[border & ~owned]) if q != SEA}

    def border(self, pid: int) -> int:
        m = self.owner
        own = m == pid
        return int(
            np.count_nonzero(own[1:] != own[:-1]) +
            np.count_nonzero(own[:, 1:] != own[:, :-1])
        )

    def legal(self, pid: int) -> list[Move]:
        if self.done or self.areas()[pid] == 0:
            return [WAIT]
        moves = [WAIT]
        for target in sorted(self.neighbours(pid)):
            for pct in PCTS:
                if self.balance[pid] * pct >= 2.0:
                    moves.append(Move(target, pct))
        return moves

    def _boundary_cells(self, pid: int, target: int) -> np.ndarray:
        m = self.owner
        mask = m == pid
        frontier = np.zeros_like(mask)
        frontier[1:] |= mask[:-1]
        frontier[:-1] |= mask[1:]
        frontier[:, 1:] |= mask[:, :-1]
        frontier[:, :-1] |= mask[:, 1:]
        return np.argwhere(frontier & (m == target))

    def _attack(self, pid: int, move: Move) -> None:
        if move.target == -3 or move.target not in self.neighbours(pid) or move.fraction not in PCTS:
            return
        sent = float(self.balance[pid] * move.fraction)
        self.balance[pid] = max(0.0, self.balance[pid] - sent)
        effective = sent * (1.0 - .01171875)
        boundary = self._boundary_cells(pid, move.target)
        if len(boundary) == 0:
            return
        # The actual game advances fronts continuously, not by this cell transfer.
        if move.target == NEUTRAL:
            density = 38.0
        else:
            defender = move.target
            count = max(1, self.areas()[defender])
            density = max(12.0, 2.0 * self.balance[defender] / count)
            self.balance[defender] = max(0.0, self.balance[defender] - sent * .42)
        amount = min(len(boundary), int(effective / density))
        if move.target == NEUTRAL and effective >= density:
            amount = max(1, amount)
        for y, x in boundary[:amount]:
            self.owner[y, x] = pid

    def _interest(self) -> None:
        areas = self.areas()
        # Approximation of early bonus decay; 0.56s/tick and 5.6s/cycle.
        interest = .01 + .06 * max(0., 1. - self.tick * .56 / 107.52)
        for pid in range(self.n):
            if areas[pid] == 0:
                self.balance[pid] = 0
                continue
            cap = 150.0 * areas[pid]
            soft = 100.0 * areas[pid]
            saturated = max(.0, min(1., (cap - self.balance[pid]) / max(1., cap - soft)))
            self.balance[pid] = min(cap, self.balance[pid] * (1. + interest * saturated))
            if self.tick % 10 == 0:
                self.balance[pid] = min(cap, self.balance[pid] + areas[pid])

    def _bot_move(self, pid: int) -> Move:
        options = self.legal(pid)
        if len(options) == 1:
            return WAIT
        neutral = [x for x in options if x.target == NEUTRAL]
        aggressive = (pid + self.seed) % 3 == 0
        if neutral and self.balance[pid] / max(1, self.areas()[pid]) >= 65:
            return Move(NEUTRAL, .30 if aggressive else .18)
        weak = [(self.balance[x.target], x.target) for x in options if x.target >= 0 and x.target != pid]
        if weak:
            _, target = min(weak)
            if self.balance[pid] > self.balance[target] * (1.2 if aggressive else 2.5):
                return Move(target, .30 if aggressive else .18)
        return WAIT

    def step(self, move: Move, pid: int = 0) -> tuple[float, bool]:
        """Return automatic reward and terminal flag; never asks for win/loss labels."""
        if self.done:
            raise RuntimeError("episode has finished")
        if move not in self.legal(pid):
            raise ValueError("illegal move")
        before = self.areas()
        prev_balance = self.balance.copy()
        self._attack(pid, move)
        # Rotated independent opponents. Same model can later replace this policy.
        for enemy in range(self.n):
            if enemy != pid and self.areas()[enemy] > 0:
                self._attack(enemy, self._bot_move(enemy))
        self.tick += 1
        self._interest()
        after = self.areas()
        alive = np.flatnonzero(after > 0)
        if len(alive) == 1:
            self.done, self.winner = True, int(alive[0])
        elif self.tick >= 600:
            self.done, self.winner = True, int(np.argmax(after))
        growth = float(after[pid] - before[pid]) / max(1, self.land)
        army = float(self.balance[pid] - prev_balance[pid]) / max(100., self.land * 50.)
        exposure = self.border(pid) / max(1., after[pid] ** .5)
        reward = 12.0 * growth + .18 * army - .0002 * exposure
        if self.done:
            reward += 2.0 if self.winner == pid else -2.0
        self.last_reward[pid] = reward
        return float(reward), bool(self.done)

    def features(self, pid: int = 0) -> np.ndarray:
        a = self.areas()
        own = int(a[pid])
        enemy = max((self.balance[i] for i in range(self.n) if i != pid and a[i]), default=0.)
        all_bal = float(self.balance.sum() + 1.)
        contacts = self.neighbours(pid)
        return np.array([
            own / max(1, self.land),
            self.balance[pid] / max(1., own * 150),
            self.balance[pid] / all_bal,
            enemy / max(1., self.balance[pid]),
            self.border(pid) / max(1., self.land),
            sum(1 for x in contacts if x >= 0) / self.n,
            float(NEUTRAL in contacts),
            self.tick / 600.,
            min(1., own * 100. / max(1., self.balance[pid])),
            min(1., self.balance[pid] / max(1., own * 100.)),
            float(max(a) == own),
            (a > 0).sum() / self.n,
            (self.owner == NEUTRAL).sum() / max(1, self.land),
            max(0., 1. - self.tick * .56 / 107.52),
            float(self.done),
            1.0 if self.winner == pid else 0.0,
        ], dtype=np.float32)

    def image(self, pid: int = 0, out: int = 18) -> np.ndarray:
        """6 channel, channels-first map tensor used by the CNN."""
        if out != self.size:
            # Fixed-grid experiments use size=18; no hidden interpolation.
            raise ValueError("out must match world.size")
        enemy = (self.owner >= 0) & (self.owner != pid)
        result = np.stack([
            self.owner == pid,
            enemy,
            self.owner == NEUTRAL,
            self.owner == SEA,
            np.full_like(self.owner, self.balance[pid] / max(1., self.land * 150.), dtype=float),
            np.full_like(self.owner, self.tick / 600., dtype=float),
        ]).astype(np.float32)
        return result

    def signature(self) -> bytes:
        return self.owner.tobytes() + np.round(self.balance, 1).tobytes() + self.tick.to_bytes(4, 'little')
