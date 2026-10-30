"""Conservative OpenCV screen vision. No injection or access to game internals.

A single initial click on the centre of YOUR coloured land is mandatory. Do
not extrapolate from crown/username alone. Borders, neutral land and water
need real-screen calibration and may be wrong on theme/zoom changes.
"""
from __future__ import annotations
from dataclasses import dataclass
import re
import numpy as np

def units(text: str) -> int | None:
    s = text.strip().replace(",", "").replace(" ", "").upper()
    s = s.replace("Ｏ", "0")
    m = re.fullmatch(r"(\d+(?:\.\d+)?)([KMB]?)", s)
    if not m:
        return None
    return int(float(m.group(1)) * {"":1, "K":1000, "M":1000000, "B":1000000000}[m.group(2)])

@dataclass
class Region:
    label: int
    area: int
    x: float
    y: float
    border: int
    rgb: tuple[int, int, int]
    kind: str
    troops: int | None = None

@dataclass
class Observation:
    own_pixels: int
    owner_rgb: tuple[int, int, int]
    own_xy: tuple[float, float]
    regions: list[Region]
    confidence: float
    own_troops: int | None = None

class ColorTracker:
    def __init__(self, initial_xy: tuple[float, float], radius: float = 34.0):
        self.seed = initial_xy
        self.radius = radius
        self.rgb: np.ndarray | None = None
        self.last_xy = initial_xy

    def _cv(self):
        try:
            import cv2
        except ImportError as err:
            raise RuntimeError("pip install alphaterr[vision]") from err
        return cv2

    def observe(self, rgb: np.ndarray) -> Observation:
        cv2 = self._cv()
        if rgb.ndim != 3 or rgb.shape[2] != 3:
            raise ValueError("expected RGB uint8 image [H,W,3]")
        h, w = rgb.shape[:2]
        x = int(np.clip(self.seed[0] * w, 0, w - 1))
        y = int(np.clip(self.seed[1] * h, 0, h - 1))
        if self.rgb is None:
            patch = rgb[max(0,y-3):y+4, max(0,x-3):x+4].reshape(-1, 3)
            self.rgb = np.median(patch, axis=0).astype(np.float32)
        pixels = rgb.astype(np.float32)
        own = (np.linalg.norm(pixels - self.rgb, axis=2) <= self.radius).astype(np.uint8)
        # Remove screenshot UI and small lettering holes, but never invent land.
        kernel = np.ones((3,3), np.uint8)
        own = cv2.morphologyEx(own, cv2.MORPH_CLOSE, kernel)
        count, labels, stats, centers = cv2.connectedComponentsWithStats(own, 8)
        best = 0
        if count > 1:
            within = labels[y, x]
            if within > 0 and stats[within, cv2.CC_STAT_AREA] >= 12:
                best = int(within)
            else:
                best = min(range(1, count), key=lambda i:
                           (centers[i,0] - self.last_xy[0]*w)**2 +
                           (centers[i,1] - self.last_xy[1]*h)**2)
        if best == 0 or stats[best, cv2.CC_STAT_AREA] < 12:
            return Observation(0, tuple(map(int,self.rgb)), self.last_xy, [], 0.)
        main = (labels == best).astype(np.uint8)
        area = int(main.sum())
        cy, cx = np.argwhere(main).mean(axis=0)
        self.last_xy = (float(cx/w), float(cy/h))
        self.rgb = .94*self.rgb + .06 * np.median(rgb[main>0], axis=0)
        grown = cv2.dilate(main, kernel, iterations=2)
        fringe = (grown>0) & (main==0)
        ys, xs = np.nonzero(fringe)
        seen = set()
        candidates = []
        # Probe a subset of border pixels, group similarly-coloured connected lands.
        for fy, fx in zip(ys[::3], xs[::3]):
            col = pixels[fy, fx]
            if np.linalg.norm(col-self.rgb) < self.radius or np.linalg.norm(col) < 45:
                continue
            quant = tuple(int(v)//24 for v in col)
            if quant in seen:
                continue
            seen.add(quant)
            delta = np.linalg.norm(pixels-col,axis=2)
            candidate = ((delta<=28) & (main==0)).astype(np.uint8)
            n, region_labels, region_stats, region_centers = cv2.connectedComponentsWithStats(candidate, 8)
            label = int(region_labels[fy,fx])
            if label == 0 or region_stats[label,cv2.CC_STAT_AREA] < 16:
                continue
            component = region_labels == label
            contact = int(np.sum(component & fringe))
            if contact < 2:
                continue
            rcx, rcy = region_centers[label]
            # Approximate ocean and neutral; game themes must be calibrated.
            r,g,b = map(int,col)
            if b>r*1.23 and b>g*1.08: kind="water"
            elif max(r,g,b)-min(r,g,b)<27: kind="neutral"
            else: kind="enemy"
            candidates.append(Region(label, int(component.sum()),float(rcx/w),
                                     float(rcy/h), contact,(r,g,b),kind))
        candidates.sort(key=lambda z: z.border, reverse=True)
        confidence = min(1.0, area/100.0)
        return Observation(area, tuple(map(int,self.rgb)), self.last_xy,
                           candidates[:20], confidence)

def ocr_read(rgb: np.ndarray, obs: Observation) -> Observation:
    """Optional Tesseract mapping by proximity; returns NONE if OCR unavailable."""
    try:
        import cv2, pytesseract
        data = pytesseract.image_to_data(
            cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR), output_type=pytesseract.Output.DICT,
            config="--psm 11")
    except (ImportError, RuntimeError, OSError):
        return obs
    h,w = rgb.shape[:2]
    texts = []
    for i, raw in enumerate(data["text"]):
        number = units(raw)
        if number is None:
            continue
        try: conf=float(data["conf"][i])
        except (TypeError,ValueError): continue
        if conf < 40:
            continue
        tx=(data["left"][i]+data["width"][i]/2)/w
        ty=(data["top"][i]+data["height"][i]/2)/h
        texts.append((tx,ty,number))
    # Text may be offset from a painted region; only assign close labels.
    for tx,ty,value in texts:
        distances = [( (tx-r.x)**2 + (ty-r.y)**2, r) for r in obs.regions]
        distance_own = (tx-obs.own_xy[0])**2+(ty-obs.own_xy[1])**2
        best = min(distances,key=lambda item:item[0]) if distances else (float("inf"),None)
        if distance_own < best[0] and distance_own < .10:
            obs.own_troops = value
        elif best[1] is not None and best[0] < .07:
            best[1].troops = value
    return obs
