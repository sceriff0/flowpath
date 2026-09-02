package qupath.ext.flowpath.analysis.session;

import qupath.ext.flowpath.model.Branch;

import java.util.Objects;

/**
 * Which branch the user chose as the report's denominator: the root gate it descends from,
 * and its gating route under that root — {@link qupath.ext.flowpath.analysis.ui.PopulationRef}'s
 * sibling, for the same reason and built the same way.
 * <p>
 * <b>Why this lives in {@code session}, not beside {@code PopulationRef} in {@code ui}.</b>
 * {@link AnalysisSession} is a Humble Object: it is deliberately constructible and
 * table-testable with no JavaFX toolkit and no QuPath objects, the same split
 * {@code UmapSession}/{@code UiStateController} make for the UMAP panel. {@code ui} already
 * depends on {@code session} (the pane reads {@code AnalysisSession}/{@code AnalysisState}),
 * so a {@code session} class reaching back into {@code ui} for {@code DenominatorRef} would
 * make that dependency bidirectional — a package cycle. It is also the more natural home on
 * its own terms: {@code AnalysisSession} is what enumerates the options
 * ({@link AnalysisSession#denominatorOptions()}) and what resolves a ref back to a live
 * {@link Branch} ({@link AnalysisSession#resolveDenominator}), and
 * {@link AnalysisSession.DenominatorOption} already lives here and carries this type.
 * {@code PopulationRef} stays in {@code ui} for the converse reason — see its own javadoc.
 * <p>
 * <b>Why a value and not the {@link Branch} itself.</b> {@code Branch} declares no
 * {@code equals}/{@code hashCode} — deliberately; {@code BranchTally} is identity-keyed
 * because two branches can share a name, and giving {@code Branch} value equality would let
 * unrelated tally entries collide. But {@code FlowPathPane.buildAnalysisInput()} calls
 * {@code gateTree.deepCopy()} on every push, and {@code GateNode.deepCopy()} mints fresh
 * {@code Branch} objects every time. A selection stored as a {@code Branch} therefore
 * compared the user's previous choice, by identity, against branches that were never going
 * to be present in the new tree — a chosen denominator went stale, and with it the
 * "% of Denominator" column, on the very next gating pass. {@code DenominatorRef} is keyed on
 * {@code (rootIndex, path)} instead, which a deep copy reproduces byte-for-byte, so
 * {@link AnalysisSession#resolveDenominator} can re-find the live {@link Branch} the ref
 * names in the new tree rather than compare pointers to a tree that no longer exists.
 * <p>
 * <b>Why the root index is part of the identity.</b> Two un-renamed root gates on the
 * identical channel emit rows with byte-identical {@code path} values, exactly as
 * {@code PopulationRef}'s javadoc explains — {@code rootIndex} is the one field that
 * resolves it, so a denominator picker built from {@code path} alone would let a user
 * "choose" a branch and silently get the other root's.
 *
 * @param rootIndex {@link qupath.ext.flowpath.model.PopulationStats.Row#rootIndex()} — the
 *                  enabled root this branch descends from
 * @param path      {@link qupath.ext.flowpath.model.PopulationStats.Row#path()} — the gating
 *                  route under that root
 */
public record DenominatorRef(int rootIndex, String path) {

    public DenominatorRef {
        Objects.requireNonNull(path, "path");
    }

    /**
     * How to name this branch to a user.
     *
     * @param disambiguateRoot append {@code " (root N)"}, one-based — needed exactly when
     *                         more than one enabled root is on offer, since the paths alone
     *                         may then be ambiguous
     */
    public String label(boolean disambiguateRoot) {
        return disambiguateRoot ? path + " (root " + (rootIndex + 1) + ")" : path;
    }
}
