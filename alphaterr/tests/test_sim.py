import pytest
import numpy as np
from alphaterr.sim import World, Move, WAIT
from alphaterr.planner import Planner, Trust
from alphaterr.vision import units

def test_repeatable_world():
    a,b = World(seed=4),World(seed=4)
    assert np.array_equal(a.owner,b.owner)
    assert np.array_equal(a.balance,b.balance)
    assert a.signature()==b.signature()

def test_legal_actions_and_no_manual_reward():
    w=World(seed=5)
    assert WAIT in w.legal(0)
    for i in range(8):
        move=Planner(depth=1,budget_ms=40).choose(w)
        assert move in w.legal(0)
        reward,done=w.step(move)
        assert np.isfinite(reward)
        assert isinstance(done,bool)

def test_reward_episode_has_no_user_win_label():
    w=World(seed=7)
    for i in range(600):
        if w.done:break
        reward,done=w.step(WAIT)
        assert np.isfinite(reward)
    assert w.done
    assert isinstance(w.winner,int)

def test_neural_weight_gated_by_heldout():
    trust=Trust()
    assert trust.neural_weight==0
    trust.games=40;trust.wins=25;trust.baseline_wins=10
    assert 0 < trust.neural_weight <= .7
    trust.baseline_wins=30
    assert trust.neural_weight==0

def test_units():
    assert units("5.8K")==5800
    assert units("12M")==12000000
    assert units("not balance") is None

def test_nonexistent_attack_rejected():
    w=World(seed=11)
    with pytest.raises(ValueError):
        w.step(Move(9,.50))
