import pytest
torch=pytest.importorskip("torch")
from alphaterr.sim import World
from alphaterr.model import AlphaTerrNet, model_inputs, legal_mask, NUM_ACTIONS
from alphaterr.train import choose_model

def test_shapes_and_action_index():
    w=World(seed=7)
    net=AlphaTerrNet()
    p,v=net(*model_inputs(w))
    assert p.shape==(1,NUM_ACTIONS)
    assert v.shape==(1,)
    move,logp,entropy,value=choose_model(net,w,.1,True)
    assert move in w.legal(0)
    assert torch.isfinite(logp)
    assert torch.isfinite(entropy)
    assert torch.isfinite(value)

def test_nnue_incremental_matches_full():
    from alphaterr.model import NNUEAccumulator,sparse_indices
    net=AlphaTerrNet()
    w=World(seed=4)
    index=sparse_indices(w)
    acc=NNUEAccumulator(net,index)
    result=acc.update(index)
    expected=net.nnue_embed(torch.as_tensor(index)).sum(0)/len(index)**.5+net.nnue_bias
    assert torch.allclose(result,expected)
