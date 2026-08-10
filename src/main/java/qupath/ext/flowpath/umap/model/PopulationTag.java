package qupath.ext.flowpath.umap.model;

/**
 * A permanent population tag applied to cells selected via polygon in UMAP space.
 *
 * @param name  population name (e.g., "Cluster A")
 * @param color packed RGB color for the ring overlay
 * @param mask  boolean array where true = cell belongs to this population
 */
public record PopulationTag(String name, int color, boolean[] mask) {

    public PopulationTag {
        mask = mask.clone();
    }

    @Override
    public boolean[] mask() {
        return mask.clone();
    }

    public int count() {
        int c = 0;
        for (boolean b : mask) if (b) c++;
        return c;
    }
}
