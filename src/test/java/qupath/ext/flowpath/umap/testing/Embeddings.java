package qupath.ext.flowpath.umap.testing;

import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.MarkerSelection;
import qupath.ext.flowpath.testing.Cells;
import qupath.ext.flowpath.umap.engine.EmbeddingFeatures;

/**
 * The UMAP half's companion to {@link Cells}: turns a population into the narrowed view
 * one embedding may read.
 * <p>
 * Deliberately <em>not</em> a method on {@code Cells}. It lived there briefly and the
 * dependency pointed the wrong way — every gating test compiled against the UMAP engine,
 * and worse, the shared fixture would <b>throw</b> for a one-marker population because of
 * a UMAP-side rule ({@link EmbeddingFeatures#MINIMUM_FEATURES}) that the gating half has
 * no opinion about. A gating population is not malformed for being unembeddable. Here the
 * dependency runs the way production's does: the UMAP testing package knows about
 * {@code Cells}, and {@code Cells} knows nothing about UMAP.
 */
public final class Embeddings {

    private Embeddings() {
    }

    /** Everything ticked — what an image saved before the feature picker loads with. */
    public static EmbeddingFeatures.Selected of(Cells cells) {
        return of(cells.build(), new MarkerSelection());
    }

    /** As {@link #of(Cells)}, for a test that already holds the index. */
    public static EmbeddingFeatures.Selected of(CellIndex index) {
        return of(index, new MarkerSelection());
    }

    /**
     * The features an embedding over {@code index} would read, given an explicit picker
     * state.
     *
     * @throws IllegalArgumentException if the selection leaves fewer than
     *         {@link EmbeddingFeatures#MINIMUM_FEATURES} markers ticked. A test that means
     *         to exercise that edge should assert on {@link EmbeddingFeatures#of} directly
     *         — reaching it through here is a fixture that cannot describe its own run
     */
    public static EmbeddingFeatures.Selected of(CellIndex index, MarkerSelection selection) {
        EmbeddingFeatures resolved = EmbeddingFeatures.of(index, selection);
        if (resolved instanceof EmbeddingFeatures.Selected selected) return selected;
        throw new IllegalArgumentException("this population cannot be embedded: "
                + ((EmbeddingFeatures.Refused) resolved).reason());
    }
}
