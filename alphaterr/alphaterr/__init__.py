"""AlphaTerr: offline simulator, classical search, CNN + NNUE and screen-only perception.

Game rules in sim.py are approximations for research, NOT the Territorial.io server.
No memory tampering, network packets, multiplayer automation or bypass functionality.
"""
from .sim import World, Move, PCTS
from .planner import Planner, Trust
__all__ = ["World", "Move", "PCTS", "Planner", "Trust"]
