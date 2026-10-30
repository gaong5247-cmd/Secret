"""Classical search playground without a browser or labels."""
import argparse
from .sim import World
from .planner import Planner
def main():
    p=argparse.ArgumentParser()
    p.add_argument("--seed",type=int,default=7)
    p.add_argument("--ticks",type=int,default=100)
    a=p.parse_args()
    world=World(seed=a.seed)
    search=Planner(depth=2,width=7,budget_ms=65)
    for tick in range(a.ticks):
        action=search.choose(world)
        reward,done=world.step(action)
        if tick%10==0 or done:
            print(f"tick={tick+1} land={world.areas().tolist()} "
                  f"balance={world.balance.astype(int).tolist()} "
                  f"move={action} reward={reward:.3f} nodes={search.nodes}")
        if done:break
    print("winner:",world.winner if world.done else "not yet terminal")
if __name__=="__main__":main()
