# Cohort gating: which paper to follow exactly for landmarks and correction

*Research brief, 2026-10-01. Five research agents, each reading primary sources and code in this session: UniFORM,
mxnorm, flowStats (Hahne 2010), CytoNorm, and independent imaging benchmarks. The synthesis author read FlowPath's
`model/cohort/{Landmarks,Density,Alignment}.java`, `cohort/{AlignmentModel,ReferenceRanking,ReviewScorer}.java` in
full, and did not re-read any paper. Every tag below is the research agent's own, carried over unchanged; none has
been upgraded.*

## Executive summary

Follow **UniFORM's correction model exactly**: one multiplicative factor per slide × marker, which is a pure shift
in natural-log space, computed from the **negative-peak landmark** (UniFORM's landmark mode). Find that landmark the
way **flowStats `gaussNorm`** does (Hahne 2010), whose density-and-peak rule FlowPath's `Density` already nearly
copies. The two agree on the correction itself: `warpSet`'s single-peak offset `x − (lm − lm_target)` is the same
shift UniFORM applies in log space.

What changes in FlowPath:
- the two-landmark stretch goes;
- the asinh cofactor heuristic goes, replaced by ln on values ≥ 1.

Hahne's design is the only one in the literature with a ground-truth evaluation of FlowPath's exact workflow (gates
drawn on one sample, transferred, scored against manual gating), but its data are flow cytometry. Nothing in the
imaging literature validates any of these methods against per-slide manual gating.

## 1. The candidates, as implementable specs

### UniFORM — Wang et al. 2025, Cell Rep Methods, [PMC12539234](https://pmc.ncbi.nlm.nih.gov/articles/PMC12539234/)

Tags: `[FULL: Results, Discussion, Limitations, STAR Methods, pseudocode images]`; supplement mmc1
`[PARTIAL: all text and Tables S6–S7; Tables S1–S5 (raster) and Fig S5 images not read]`;
code [kunlunW/UniFORM](https://github.com/kunlunW/UniFORM) @ c750a9a `[FULL: the five .py files]`.

1. **Transform.** Natural log. Values < 1 are **dropped** from the histogram (`preprocessing.py:30-64`). No
   cofactor. The paper's GMM pseudocode says `log10(X+1)`, which conflicts with the code.
2. **Histogram.** 1024 bins over the per-marker global log range across all samples; raw counts, no smoothing
   (`preprocessing.py:314-358`).
3. **Reference.**
   - Automatic mode: the sample whose raw-count histogram has the smallest L2 distance to the mean histogram.
   - Landmark mode: the sample whose landmark is nearest the mean landmark.
   - The user can override either (`registration.py:286-303`).
   - The choice is made **per marker**.
4. **Shift, automatic mode.** The argmax of the FFT cross-correlation of the whole histograms, in integer bins
   (`registration.py:150-180`). It does **not** target the negative peak, so it can lock onto a dominant positive peak.
5. **Shift, landmark mode.** `s = L_i − L_ref`. Landmarks are bin indices from a user-filled CSV, so the paper
   finds them by hand (`registration.py:119-147`).
6. **Correction.** `x_norm = x_raw · exp(−s·Δ)`, one factor per (sample, marker), applied to every raw value
   including those < 1 (`normalization.py:224-267, 380-400`).
7. **Missing peak.** Not handled. There is no confidence and no fallback, and a sample with no value ≥ 1 crashes.
8. **QC.** The Discussion mentions a "KL divergence-based" flag. It is **defined nowhere**: not in the main text,
   the STAR Methods, the supplement text, or the code. **It cannot be followed.**
9. **Evidence.** Three imaging datasets: ORION (6 samples), PRAD-CyCIF (20 patients, 7 batches), Lunaphore TMA.
   - kBET acceptance: UniFORM 0.608, mean division 0.542, MxNorm 0.388.
   - Silhouette: UniFORM 0.487, MxNorm −0.021.
   - There is no external ground truth; the "positive % preserved" metric uses each raw sample's own GMM threshold
     as truth.
   - UniFORM is not the best on PRAD EPCAM or CD45.
10. **Stated failure modes.** The negative population is assumed invariant. Multimodal markers and inconsistent
    positive:negative ratios break it. It is unsuitable for mIHC.
11. **Licence.** No LICENSE file. File headers say MIT, Zenodo says CC-BY-4.0, the article is CC BY-NC.
    Reimplement from the spec; don't copy.

### flowStats gaussNorm / warpSet — Hahne et al. 2010, Cytometry A, [doi:10.1002/cyto.a.20823](https://doi.org/10.1002/cyto.a.20823), PMC3648208

Tags: `[PARTIAL: whole main text of the author manuscript; missing Algorithm 1 body (image), figures, supplement
S1–S9]`; code [RGLab/flowStats](https://github.com/RGLab/flowStats) `devel` @ fabb131 (2023-07-19)
`[FULL: gaussNorm.R, warpSet.R cytoset method, landmarkMatrix.R, curvPeaks.R, autoGate.R curv1Filter, feature.R 1D]`;
fda `landmarkreg` internals `[MEMORY]`.

**gaussNorm** (`gaussNorm.R`). Defaults: `max.lms=2, peak.density.thr=0.05, peak.distance.thr=0.05`. Input is
assumed to be already transformed.

1. **Density.** `density()` with its defaults: bw.nrd0 (Silverman's rule of thumb), 512 grid points. A peak is a
   grid point that is the maximum of a 4-point window (`landmarker`, :578).
2. **Score** (`score.lms`, :625).
   - Zero if the peak's height is < 0.05 × the tallest peak.
   - Otherwise Σ(y_p − y_i) over ±min(64, 512/10)/2 grid points, with negative terms ×3, multiplied by y_p and
     floored at 0.
   - Of two peaks closer than 0.05 × the grid range, only the higher-scoring one is kept.
   - The top `max.lms` peaks with score > 0 are kept.
3. **Target.** The cross-sample **median** of each landmark class, or a user-supplied `base.lms`. It calls `stop()`
   if no sample has exactly `max.lms` peaks.
4. **Matching.** An order-preserving subset match maximising Σ score²/|lm − base|. This **differs from the paper**
   ("minimum sum of distances").
5. **Correction** (`register.function`, :491).
   - One landmark: a pure shift.
   - Two or more: Gaussian-weighted local shifts, with no guarantee of monotonicity.
6. **Missing landmark.** Not moved. Confidence is ×0.6 per missing landmark.

**warpSet / fdaNorm** (`warpSet.R`). Defaults: `bwFac=2, nbreaks=11, clipRange=0.01`.

1. **Peaks.** curv1Filter:
   - bandwidth `min(sd, IQR/1.349)·(4/(7n))^(1/9)` × 2;
   - `featureSignif` significant negative curvature at α = 0.05 with a step-up correction;
   - the `optimize` maximum within each interval.
2. **Peak count.** The largest count seen in more than 10% of samples (:40). This is not the mode the paper
   describes. k-means labelling with random starts can leave all-NA columns (TODO at :108).
3. **NA fill.** The column **mean** (:220), although the comment says median.
4. **Correction.**
   - Single peak: the offset `x − (lm − lm_target)` (:231).
   - Several peaks: `landmarkreg`, a monotone warp pinned at both ends.

**Evidence** (flow cytometry, 30 DLBCL samples + ITN). Static gates were drawn on one sample and scored by Jaccard
against manual gating.
- Overlap was "similar or higher" after normalisation for every gate, and above 95% for large and medium
  populations.
- 4 of 180 gates stayed below 50%, all from a missed CD3 landmark.
- FlowJo magnetic gates failed on 12 of 29 samples.
- QC rule: samples with missing or outlying landmarks are inspected by hand.
- Assumption (iv): only the +/− proportions carry the signal; MFI may be rescaled by any order-preserving map.

**Licence.** Artistic-2.0.

### mxnorm — Harris et al. 2022, Bioinformatics, [PMC8896603](https://pmc.ncbi.nlm.nih.gov/articles/PMC8896603/)

Tags: `[FULL: main text incl. MathML equations; figure images not viewed]`; supplement `[PARTIAL: text; figure
images missing]`; code [ColemanRHarris/mxnorm](https://github.com/ColemanRHarris/mxnorm) @ fc9e6d3 `[FULL: cited R
files]`.

1. **Mean division.** y / μ_ic, where μ_ic is the mean over **all cells** on the slide for that marker. This equals a
   shift of log y by log μ_ic.
2. **Mean division + log10.** `log10(y/μ + 0.5)`, which is close to a shift only for y ≫ μ/2.
3. **Registration.** fda `register.fd` toward the cell-count-weighted mean density. It uses **no landmarks**.
4. **Metric.** Otsu discordance: the fraction of a slide's cells on which the slide's own Otsu threshold and the
   global Otsu threshold disagree.
5. **Results** (43 slides, MxIF colorectal cancer, mean over 9 markers).
   - Mean division had the lowest discordance: **0.041**, against 0.085 for raw.
   - Mean division + log10 + registration: 0.049.
   - Mean division + registration was the worst: 0.164.
   - Only the mean-division methods beat raw.
6. **Weakness.** μ depends on composition: a slide with more positive cells is divided by more. The paper says only
   that slide effects are confounded with biology.
7. **Code issue.** A possible row-order bug in `run_registration`, found from reading only, not run.
8. **Licence.** MIT.

### CytoNorm — Van Gassen et al. 2020, Cytometry A, [PMC7078957](https://pmc.ncbi.nlm.nih.gov/articles/PMC7078957/)

Tags: `[PARTIAL: full main text; supplement figures missing]`; CytoNorm 2.0 (Quintelier 2025, PMID 39871681)
`[ABSTRACT]`, paywalled; code [saeyslab/CytoNorm](https://github.com/saeyslab/CytoNorm) @ 278f352
`[FULL: R/QuantileNorm.R]`.

1. **Method.** FlowSOM clusters, then 99 quantiles per batch × cluster × channel, then a monotone
   Fritsch–Carlson spline onto the goal quantiles.
2. **Goal.** Defaults to the mean across batches; can be one batch. The static-gating experiment used the first
   control sample as the goal.
3. **Extrapolation** is linear, from the end slope.
4. **Unsuitable here.** It needs a shared control, or a pool of many samples, per batch. With one slide per batch, it
   maps each slide's within-cluster distribution onto the reference's, so the reference's positive fraction is
   imposed on every slide.
5. **No published use on tissue imaging was found.**
6. **Licence.** GPL (≥ 2).

## 2. Independent evidence

There is **no independent benchmark** that scores slide-to-slide threshold transfer against per-slide manual gating
while positive fractions vary.
- Harris 2022 and UniFORM 2025 each benchmarked the other's method family, and each won on its own metric.
- A bioRxiv preprint (Kharbanda et al. 2026, bgnorm, doi:10.64898/2026.08.05.743141)
  `[PARTIAL: main text + methods; supplement not retrieved]` used expert per-cell labels on 3 CODEX fields of view.
  UniFORM had high precision but less than half of bgnorm's recall. That is within-slide, not between slides, and
  it is the bgnorm authors' own benchmark.
- Eng et al. 2022 ([PMC9095647](https://pmc.ncbi.nlm.nih.gov/articles/PMC9095647/)) `[PARTIAL: batch-normalisation
  results + methods; missing Suppl. Figs 15–17]` is the only direct evidence of composition confounding:
  location–scale correction absorbs differences in tissue mix.
- No study isolates shift-only from shift + stretch at fixed landmarks.
- Semantic Scholar was rate-limited and was not searched.

## 3. FlowPath today versus the recommendation

| Step | FlowPath now | Follow exactly | Source |
|---|---|---|---|
| Transform | asinh(x/c), c = median \|x\| of the reference's clean cells (a FlowPath heuristic) | ln(x), only values ≥ 1 enter the density | UniFORM `preprocessing.py:30-64` |
| Density | Gaussian KDE, 512 grid, 0.9·spread·n^-0.2 | `density()` defaults: bw.nrd0, 512 grid | gaussNorm `landmarker` |
| Peak rule | topographic prominence ≥ 5% of the tallest | height ≥ 0.05 × tallest, 4-point local max, merge within 0.05 × range, score as `score.lms` | gaussNorm `score.lms`, `filter.lms` |
| L1 | lowest prominent peak | lower of the top `max.lms=2` scored peaks (the negative population) | gaussNorm, with FlowPath's own "lowest is the negative" choice |
| Reference landmark | the reference slide's L1 | the reference sample's landmark (UniFORM aligns to it, not to the mean) | UniFORM landmark mode |
| Correction | TWO_LANDMARK stretch if both L2, else SHIFT, in asinh space | **shift only**: `applied = t · exp(L1_slide − L1_ref)` in raw units | UniFORM `normalization.py`; `warpSet` single-peak offset |
| No L1 | identity + flag | identity + flag, inspect by hand | Hahne QC rule |
| Outlier flag | shift or stretch > 3 MAD from the cohort median | shift only; landmark outlier → inspect (the 3-MAD cut stays FlowPath's) | Hahne |
| KL / shape flag | none | none possible (undefined in UniFORM) | — |

Consequences:
- **Composition.** A shift on the negative peak never fits the positive population, so a slide with genuinely fewer
  positives is not normalised toward the reference. Removing this failure path is the main gain.
- **Bright and dim range.** In asinh space, FlowPath's shift is multiplicative only well above the cofactor. ln makes
  it exactly multiplicative everywhere, which is what UniFORM and mean division both do.
- **Data below 1.** MIRAGE's values are cell medians of uint16 pixels, so most cells are ≥ 1. A
  background-subtracted channel with most cells < 1 would have no landmark under UniFORM's rule and would stay
  uncorrected. That is safe (identity + flag) but should be checked on real data.
- **No format change.** Thresholds stay in reference units. Applied values, the landmark cache and the review
  change, and `ReferenceRanking` should use the same ln landmarks so eligibility and alignment cannot disagree.

## Research vs. shipping

- The UniFORM paper describes an automatic, negative-peak method, but its automatic code mode cross-correlates
  whole histograms and its landmark mode needs hand-picked landmarks. **FlowPath would ship the combination that
  neither paper ships:** automatic negative-peak landmarks (gaussNorm) feeding a shift-only log correction (UniFORM).
- flowStats' code diverges from its paper on matching, the peak count and the NA fill. "Follow exactly" therefore
  means the code at a pinned commit (fabb131), not the paper's prose.

## Conflicts and gaps

- Transform: UniFORM's code uses ln on values ≥ 1; its pseudocode says log10(X+1).
- Mean division (Harris) vs negative-peak shift (UniFORM): each wins on its own metric, and neither has ground truth.
- The KL flag is undefined.
- Hahne's ground-truth evidence is flow cytometry, not imaging.
- Not checked: the UniFORM bioRxiv preprint, Tables S1–S5, the fda internals, and GammaGateR (title only).
- A FlowPath synthetic test would settle shift vs stretch for this workflow: same staining, different positive
  fractions, with a known truth.

## Papers

- Wang et al. 2025, Cell Rep Methods 5:101172, doi:10.1016/j.crmeth.2025.101172, PMC12539234 — tags above.
- Hahne et al. 2010, Cytometry A, doi:10.1002/cyto.a.20823, PMC3648208 — tags above.
- Harris et al. 2022, Bioinformatics, doi:10.1093/bioinformatics/btab877, PMC8896603 — tags above.
- Van Gassen et al. 2020, Cytometry A, PMC7078957 — tags above.
- Quintelier et al. 2025, Cytometry A, doi:10.1002/cyto.a.24910 `[ABSTRACT]`.
- Kharbanda et al. 2026, bioRxiv, doi:10.64898/2026.08.05.743141 `[PARTIAL: main text + methods]`.
- Eng et al. 2022, Commun Biol, doi:10.1038/s42003-022-03368-y, PMC9095647 `[PARTIAL]`.
- Hickey et al. 2021, Front Immunol, PMC8415085 `[PARTIAL: methods + results]`.
- Frei et al. 2023, J Pathol Clin Res, PMC10556275 `[PARTIAL: methods, results, discussion]`.

## Repos

| Repo | Commit read | Licence | Maintenance |
|---|---|---|---|
| kunlunW/UniFORM | c750a9a | no LICENSE file (MIT headers) | 19 stars, active (2026-08) |
| RGLab/flowStats | fabb131 | Artistic-2.0 | 15 stars, stale (2023-07) |
| ColemanRHarris/mxnorm | fc9e6d3 | MIT | 8 stars, CRAN 1.1.0 (2025-09) |
| saeyslab/CytoNorm | 278f352 | GPL (≥ 2) | 41 stars, active (2026-07) |
