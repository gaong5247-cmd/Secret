"""No manually labelled wins or losses: rewards come from World.step.

Classical lookahead supplies an initial action prior, but gradients come ONLY
from observed simulated rewards (advantage actor-critic). Trust moves weight
to the model only after held-out seeded matches beat the classical baseline.
"""
from __future__ import annotations
import argparse
from pathlib import Path
import numpy as np
import torch
from torch.distributions import Categorical
from .sim import World, Move
from .planner import classical_score, Planner, Trust
from .model import AlphaTerrNet, model_inputs, legal_mask, action_from_index

def classical_prior(world: World) -> torch.Tensor:
    from .model import NUM_ACTIONS, action_index
    scores = torch.full((NUM_ACTIONS,), -100., dtype=torch.float32)
    for a in world.legal(0):
        nxt = world.clone()
        nxt.step(a)
        scores[action_index(a)] = classical_score(nxt)
    ok = legal_mask(world)
    vals = scores[ok]
    if len(vals) > 1:
        scores[ok] = (vals - vals.mean()) / vals.std(unbiased=False).clamp_min(1.0)
    return scores

def choose_model(model: AlphaTerrNet, world: World, weight: float,
                 explore: bool = True):
    logits, val = model(*model_inputs(world))
    logits = logits.squeeze(0)
    mask = legal_mask(world)
    priors = classical_prior(world) if weight < 1.0 else torch.zeros_like(logits)
    mix = ((1. - weight) * priors * 1.6 + weight * logits).masked_fill(~mask, -1e9)
    dist = Categorical(logits=mix)
    choice = dist.sample() if explore else mix.argmax()
    return action_from_index(int(choice)), dist.log_prob(choice), dist.entropy(), val.squeeze(0)

def train(episodes: int = 80, seed: int = 0, max_steps: int = 120, path: str = "alphaterr.pt",
          evaluation_interval: int = 20) -> dict:
    torch.manual_seed(seed)
    model = AlphaTerrNet()
    optim = torch.optim.AdamW(model.parameters(), lr=4e-4, weight_decay=1e-4)
    trust = Trust()
    results = []
    for ep in range(episodes):
        world = World(seed=seed + ep)
        logps, values, entropies, rewards = [], [], [], []
        model.train()
        # Initial classical dominance, then increase NN influence only
        # when its *held-out reward wins* exceed the baseline.
        influence = max(.08, trust.neural_weight)
        for _ in range(max_steps):
            move, logp, entropy, value = choose_model(model, world, influence)
            reward, done = world.step(move)
            logps.append(logp)
            values.append(value)
            entropies.append(entropy)
            rewards.append(reward)
            if done:
                break
        returns = []
        R = 0.
        for reward in reversed(rewards):
            R = reward + .98 * R
            returns.append(R)
        returns.reverse()
        ret = torch.as_tensor(returns, dtype=torch.float32)
        val = torch.stack(values)
        advantage = ret - val.detach()
        actor = -(torch.stack(logps) * advantage).mean()
        critic = .5 * (ret - val).square().mean()
        entropy_loss = -.003 * torch.stack(entropies).mean()
        loss = actor + critic + entropy_loss
        optim.zero_grad()
        loss.backward()
        torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        optim.step()
        results.append(float(sum(rewards)))
        if (ep + 1) % evaluation_interval == 0:
            score = evaluate(model, seed + 100000 + ep * 100, games=40, max_steps=max_steps)
            trust.games += score["games"]
            trust.wins += score["neural_wins"]
            trust.baseline_wins += score["baseline_wins"]
            print(f"ep={ep+1} return={np.mean(results[-evaluation_interval:]):.3f} "
                  f"bench(model/base)={score['neural_wins']}/{score['baseline_wins']} "
                  f"trust={trust.neural_weight:.2f}")
    checkpoint = {
        "model": model.state_dict(), "episodes": episodes,
        "seed": seed, "eval_games": trust.games,
        "wins": trust.wins, "baseline_wins": trust.baseline_wins,
        "neural_weight": trust.neural_weight,
        "simulator_warning": "approximate offline world, NOT Territorial.io",
    }
    torch.save(checkpoint, path)
    return {k: v for k, v in checkpoint.items() if k != "model"}

@torch.no_grad()
def evaluate(model: AlphaTerrNet, seed: int = 1000, games: int = 40,
             max_steps: int = 120) -> dict:
    model.eval()
    scores = {"games": games, "neural_wins": 0, "baseline_wins": 0}
    for i in range(games):
        for neural in (False, True):
            w = World(seed=seed + i)
            planner = Planner(depth=2, width=6, budget_ms=25) if not neural else None
            for _ in range(max_steps):
                action = (choose_model(model, w, 1.0, False)[0] if neural
                          else planner.choose(w))
                _, done = w.step(action)
                if done:
                    break
            winner = w.winner if w.done else int(np.argmax(w.areas()))
            if winner == 0:
                scores["neural_wins" if neural else "baseline_wins"] += 1
    return scores

def main():
    p = argparse.ArgumentParser(description="Reward-only AlphaTerr offline training")
    p.add_argument("--episodes", type=int, default=80)
    p.add_argument("--seed", type=int, default=0)
    p.add_argument("--steps", type=int, default=120)
    p.add_argument("--out", default="alphaterr.pt")
    p.add_argument("--eval-every", type=int, default=20)
    args = p.parse_args()
    if args.episodes < 1 or args.steps < 1 or args.eval_every < 1:
        p.error("episodes, steps and eval-every must be positive")
    print(train(args.episodes, args.seed, args.steps, args.out, args.eval_every))

if __name__ == "__main__":
    main()
