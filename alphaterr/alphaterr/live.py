"""Optional desktop pixel-only observer/controller, OFF by default.

For a user-confirmed singleplayer bot match only. Never joins rooms, modifies
the game, reads process memory, sends packets or bypasses anti-bot protections.
"""
from __future__ import annotations
import argparse
import time
import numpy as np
from .vision import ColorTracker, ocr_read, Observation

def decide(obs: Observation):
    """(target, fraction) or None, with conservative no-OCR stop."""
    if obs.confidence < .7 or obs.own_troops is None or obs.own_troops < 200:
        return None
    enemies = [r for r in obs.regions if r.kind == "enemy" and r.troops is not None
               and r.troops > 0]
    targets = [r for r in enemies if obs.own_troops > 2.6*r.troops]
    if not targets:
        return None
    target = max(targets,key=lambda r:r.area/(1.+r.troops/1000.) + r.border*.5)
    required = 2.4*target.troops/obs.own_troops
    fraction = next((v for v in (.06,.10,.18,.30,.45) if v >= required), None)
    if fraction is None or obs.own_troops*(1.-fraction) < 1.6*target.troops:
        return None
    return target, fraction

def main():
    p=argparse.ArgumentParser(description="Observe only by default; manual colour calibration")
    p.add_argument("--own-x", type=float, required=True, help="own land seed X in screen pixels")
    p.add_argument("--own-y", type=float, required=True, help="own land seed Y in screen pixels")
    p.add_argument("--enable-input",action="store_true")
    p.add_argument("--singleplayer-confirmed",action="store_true")
    p.add_argument("--slider",nargs=3,type=float,metavar=("LEFT","RIGHT","Y"))
    p.add_argument("--sword",nargs=2,type=float,metavar=("X","Y"))
    p.add_argument("--period",type=float,default=2.)
    p.add_argument("--frames",type=int,default=1)
    a=p.parse_args()
    if a.enable_input and (not a.singleplayer_confirmed or a.slider is None):
        p.error("input needs --singleplayer-confirmed and calibrated --slider")
    if a.enable_input and a.period < 1.5:
        p.error("period must be >= 1.5 seconds for input")
    try:
        import mss
    except ImportError:
        p.error("pip install '.[vision]' first")
    try:
        import pyautogui
    except ImportError:
        pyautogui=None
    if a.enable_input and pyautogui is None:
        p.error("pip install pyautogui for input")
    with mss.mss() as grabber:
        monitor=grabber.monitors[1]
        tracker=ColorTracker(((a.own_x-monitor["left"])/monitor["width"],
                              (a.own_y-monitor["top"])/monitor["height"]))
        for frame in range(a.frames if a.frames>0 else 10**9):
            shot=np.asarray(grabber.grab(monitor))[:,:,:3][:,:,::-1].copy()
            obs=ocr_read(shot,tracker.observe(shot))
            action=decide(obs)
            print("frame",frame,"owned",obs.own_pixels,"troops",obs.own_troops,
                  "enemies",len(obs.regions),"confidence",round(obs.confidence,2),
                  "plan",None if action is None else
                    (round(action[0].x,3),round(action[0].y,3),action[1]))
            if a.enable_input and action:
                target,fraction=action
                left,right,sy=a.slider
                sx=left+(right-left)*fraction
                pyautogui.click(sx,sy)
                x=monitor["left"]+int(target.x*monitor["width"])
                y=monitor["top"]+int(target.y*monitor["height"])
                pyautogui.click(x,y)
                if a.sword:
                    time.sleep(.14)
                    pyautogui.click(*a.sword)
            time.sleep(max(.2,a.period))
if __name__=="__main__":
    main()
