# Citation

If you use FlowPath in published work, please cite it and the tools it builds
on. If you also used [MIRAGE](https://mirage-pipeline.readthedocs.io/) upstream,
cite that too.

## FlowPath

> FlowPath: Interactive tree-based cell phenotyping and UMAP exploration for
> QuPath. (2026).
> <https://github.com/sceriff0/flowpath>

!!! note "Citing earlier versions"
    FlowPath's version numbering restarted at **0.9.0**; earlier releases formed a
    separate 1.x / 2.x line, and version numbers do not compare across the two. Earlier
    still, it shipped as two extensions, *FlowPath - GatingTree* and *FlowPath - qUMAP*.
    Work done with any of those should cite them by the names, versions and URLs current
    at the time. The reference above covers 0.9.0 onward, where they are one tool.

    **Always state the version you used** — the reference alone does not identify it, and
    the two numbering lines overlap.

If you imported cells with
[AnnoMask](https://github.com/sceriff0/qupath-extension-annomask) — a separate
extension — cite that as well:

> FlowPath - AnnoMask: labelled-mask import for QuPath. (2026).
> <https://github.com/sceriff0/qupath-extension-annomask>

## QuPath

FlowPath runs on QuPath — please cite it:

> Bankhead, P. et al. (2017). QuPath: Open source software for digital pathology
> image analysis. *Scientific Reports*, 7, 16878.
> <https://doi.org/10.1038/s41598-017-17204-5>

## UMAP & SMILE (if you computed an embedding)

If you computed an embedding, also cite the UMAP algorithm and the SMILE library
that implements it:

> McInnes, L., Healy, J., & Melville, J. (2018). UMAP: Uniform Manifold
> Approximation and Projection for Dimension Reduction. *arXiv:1802.03426*.
> <https://arxiv.org/abs/1802.03426>

> Haifeng Li. (2014). SMILE — Statistical Machine Intelligence and Learning
> Engine. <https://haifengl.github.io/>

## MIRAGE (upstream pipeline)

If you produced your inputs with MIRAGE, cite it as well — see MIRAGE's own
[citation page](https://mirage-pipeline.readthedocs.io/) for the current reference.

!!! tip "Cite what you used"
    A pure import-and-gate workflow needs FlowPath and QuPath (+ AnnoMask if you
    imported a mask); add the UMAP and SMILE references only if you computed an
    embedding.
