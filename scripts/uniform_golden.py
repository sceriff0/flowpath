"""Golden fixtures for FlowPath's UniFORM port. Run:
uv run --with numpy==1.26.4 --with scipy==1.13.1 --with scikit-image scripts/uniform_golden.py
Reproduces kunlunW/UniFORM @ c750a9a: ln of values >= 1, np.histogram(1024, global range),
scipy.signal.correlate(h, ref, 'full', method='direct'), argmax - (N-1), increment (max-min)/(N-1).
"""
import json, numpy as np
from scipy.signal import correlate
from skimage.filters import threshold_otsu

rng = np.random.default_rng(20261001)
N = 1024

def sample(neg_mu, pos_mu, pos_frac, n, scale):
    neg = rng.lognormal(neg_mu, 0.35, int(n * (1 - pos_frac)))
    pos = rng.lognormal(pos_mu, 0.30, int(n * pos_frac))
    return np.concatenate([neg, pos]) * scale

cases = []
slides = {
    "ref": sample(4.0, 6.0, 0.20, 5000, 1.0),
    "bright": sample(4.0, 6.0, 0.20, 5000, 1.6),
    "dim": sample(4.0, 6.0, 0.35, 4000, 0.7),
    "posdominant": sample(4.0, 6.0, 0.85, 5000, 1.2),
    "subone": np.concatenate([rng.uniform(0, 0.99, 3000), sample(4.0, 6.0, 0.2, 1000, 1.0)]),
}
logs = {k: np.log(v[v >= 1.0]) for k, v in slides.items()}
gmin = min(l.min() for l in logs.values()); gmax = max(l.max() for l in logs.values())
hists = {k: np.histogram(l, bins=N, range=(gmin, gmax))[0] for k, l in logs.items()}
inc = (gmax - gmin) / (N - 1)
for k in slides:
    corr = correlate(hists[k].astype(np.int64), hists["ref"].astype(np.int64), mode="full", method="direct")
    s = int(np.argmax(corr) - (N - 1))
    cases.append({"slide": k, "shiftBins": s, "logShift": s * inc, "factor": float(np.exp(s * inc))})

otsu = {k: float(threshold_otsu(l, nbins=256)) for k, l in logs.items()}
out = {
    "raw": {k: v.tolist() for k, v in slides.items()},
    "gridMin": gmin, "gridMax": gmax,
    "histograms": {k: h.tolist() for k, h in hists.items()},
    "shifts": cases,
    "otsu": otsu,
}
with open("src/test/resources/qupath/ext/flowpath/cohort/uniform-golden.json", "w") as f:
    json.dump(out, f)
print("wrote", {c["slide"]: c["shiftBins"] for c in cases})
