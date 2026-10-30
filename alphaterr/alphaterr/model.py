"""CPU/GPU-compatible CNN + sparse NNUE-style accumulator.

Mobile conversion is NOT bundled. Torch weights are an actual trainable model,
but must pass held-out evaluation and an export process before Android use.
"""
from __future__ import annotations
import numpy as np
import torch
from torch import nn
from torch.nn import functional as F
from .sim import World, Move, PCTS

CELLS = 18 * 18
SPARSE_FEATURES = CELLS * 4 + 16 * 8
NUM_ACTIONS = 1 + 6 * len(PCTS)  # 5 players + neutral + wait

def sparse_indices(world: World, pid: int = 0) -> np.ndarray:
    if world.size != 18 or world.n != 5:
        raise ValueError("neural architecture currently fixed to size=18, players=5")
    m = world.owner.reshape(-1)
    roles = np.where(m == pid, 0, np.where(m == -1, 2, np.where(m == -2, 3, 1)))
    spatial = np.arange(CELLS, dtype=np.int64) * 4 + roles
    scalar = np.clip((world.features(pid) * 7).astype(np.int64), 0, 7)
    return np.concatenate([spatial, CELLS * 4 + 8 * np.arange(16) + scalar])

def action_index(move: Move) -> int:
    if move.target == -3:
        return 0
    return 1 + (move.target + 1) * len(PCTS) + PCTS.index(move.fraction)

def action_from_index(index: int) -> Move:
    if index == 0:
        return Move(-3, 0.)
    pos = index - 1
    return Move(pos // len(PCTS) - 1, PCTS[pos % len(PCTS)])

def legal_mask(world: World, pid: int = 0) -> torch.Tensor:
    mask = torch.zeros(NUM_ACTIONS, dtype=torch.bool)
    for a in world.legal(pid):
        mask[action_index(a)] = True
    return mask

class AlphaTerrNet(nn.Module):
    """CNN for spatial fronts + efficiently updatable NNUE feature stream."""
    def __init__(self, hidden: int = 64):
        super().__init__()
        self.nnue_embed = nn.Embedding(SPARSE_FEATURES, hidden)
        self.nnue_bias = nn.Parameter(torch.zeros(hidden))
        self.conv = nn.Sequential(
            nn.Conv2d(6, 16, 3, padding=1), nn.ReLU(),
            nn.Conv2d(16, 32, 3, padding=1), nn.ReLU(),
            nn.Conv2d(32, 32, 3, padding=1), nn.ReLU(),
            nn.AdaptiveAvgPool2d(1), nn.Flatten(),
        )
        self.scalar = nn.Sequential(nn.Linear(16, 32), nn.ReLU())
        self.fuse = nn.Sequential(nn.Linear(hidden + 32 + 32, 128), nn.ReLU())
        self.policy = nn.Linear(128, NUM_ACTIONS)
        self.value = nn.Sequential(nn.Linear(128, 1), nn.Tanh())

    def forward(self, image, scalars, sparse):
        # Same active feature set can be updated by additions/subtractions.
        acc = self.nnue_embed(sparse).sum(1) / (sparse.shape[1] ** .5)
        h = F.relu(acc + self.nnue_bias)
        fused = self.fuse(torch.cat((h, self.conv(image), self.scalar(scalars)), 1))
        return self.policy(fused), self.value(fused).squeeze(-1)

    def evaluate(self, world: World, pid: int = 0) -> float:
        self.eval()
        with torch.no_grad():
            _, value = self(*model_inputs(world, pid))
        return float(value.item())

class NNUEAccumulator:
    """Fast incremental sparse accumulator, without rerunning embeddings."""
    def __init__(self, net: AlphaTerrNet, indices: np.ndarray):
        self.net = net
        self.ids = set(map(int, indices))
        with torch.no_grad():
            self.raw = net.nnue_embed(torch.tensor(sorted(self.ids))).sum(0)
    def update(self, new_indices: np.ndarray):
        new_ids = set(map(int, new_indices))
        with torch.no_grad():
            for old in self.ids - new_ids:
                self.raw -= self.net.nnue_embed.weight[old]
            for added in new_ids - self.ids:
                self.raw += self.net.nnue_embed.weight[added]
        self.ids = new_ids
        return self.raw / max(1, len(new_ids)) ** .5 + self.net.nnue_bias

def model_inputs(world: World, pid: int = 0):
    device = next(iter([torch.device("cpu")]), torch.device("cpu"))
    return (
        torch.from_numpy(world.image(pid)[None]).to(device),
        torch.from_numpy(world.features(pid)[None]).to(device),
        torch.from_numpy(sparse_indices(world, pid)[None]).to(device),
    )
