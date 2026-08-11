package qupath.ext.flowpath.model;

/**
 * A per-compartment summary statistic stored by the MIRAGE pipeline.
 * <p>
 * Maps to the statistic token in the measurement key
 * {@code "<marker>: <Compartment>: <Stat>"}. <b>{@link #MEDIAN} is the one MIRAGE
 * always emits</b> for every compartment it quantifies; {@link #MEAN} and
 * {@link #SUM} appear only with {@code --expanded_quantification}. Which of the
 * three a given export actually carries must be read from
 * {@link CompartmentCapability}, never assumed — a default-quantification export
 * has no Mean column, and pinning a gate axis to one resolves it to a measurement
 * key that is not in the file, so every cell reads NaN.
 * <p>
 * Orthogonal to FlowPath's live z-score toggle, which is applied on top of
 * whichever statistic is selected.
 */
public enum Statistic {

    MEAN("Mean"),
    MEDIAN("Median"),
    SUM("Sum");

    private final String token;

    Statistic(String token) {
        this.token = token;
    }

    /** Token used inside the measurement key, e.g. {@code "Mean"}. */
    public String token() {
        return token;
    }

    /** Human-readable name for UI (same as the token). */
    public String displayName() {
        return token;
    }

    /**
     * The statistic the <em>bare</em> marker column holds — not the statistic a new gate
     * should default to.
     * <p>
     * {@code CellIndex} treats {@code (WHOLE_CELL, MEAN)} as the one selection that
     * resolves to the bare {@code "CD3"} column, which MIRAGE defines as the whole-cell
     * mean. That is why this is {@link #MEAN} while the gate model's own field default is
     * {@link #MEDIAN}: the two answer different questions. Gates pick their statistic from
     * {@link CompartmentCapability} via {@code GateEditorPane.chooseStatistic}.
     */
    public static Statistic defaultStatistic() {
        return MEAN;
    }

    /** Parse a statistic token (case-insensitive); returns null if unrecognised. */
    public static Statistic fromToken(String token) {
        if (token == null) return null;
        for (Statistic s : values()) {
            if (s.token.equalsIgnoreCase(token)) return s;
        }
        return null;
    }
}
