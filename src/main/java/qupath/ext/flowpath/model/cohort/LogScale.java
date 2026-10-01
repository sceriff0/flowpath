package qupath.ext.flowpath.model.cohort;

/**
 * The log scale a column's shift is estimated on. {@link #LN} is UniFORM's (Wang et al. 2025, code
 * kunlunW/UniFORM @ c750a9a, preprocessing.py:30-64 [FULL]): the natural log of values >= 1, every
 * other value ignored. {@link #LN1P} is a documented FlowPath departure (spec U4): ln(x + 1) of
 * values >= 0, so cells below 1 count too. Either way the correction is one multiplicative factor
 * applied to every value.
 */
public enum LogScale {
    LN("ln", "ln(x), values ≥ 1 (UniFORM)"),
    LN1P("ln1p", "ln(x + 1), all values (departure from UniFORM)");

    private final String token;
    private final String description;

    LogScale(String token, String description) {
        this.token = token;
        this.description = description;
    }

    /** NaN when {@code raw} is outside this scale's domain, or not finite. */
    public double toLog(double raw) {
        if (!Double.isFinite(raw)) return Double.NaN;
        return switch (this) {
            case LN -> raw >= 1.0 ? Math.log(raw) : Double.NaN;
            case LN1P -> raw >= 0.0 ? Math.log1p(raw) : Double.NaN;
        };
    }

    public double fromLog(double u) {
        return this == LN ? Math.exp(u) : Math.expm1(u);
    }

    public String token() { return token; }

    public String describe() { return description; }

    /** Unknown or null tokens fall back to {@link #LN}: an unreadable setting must not silently depart from UniFORM. */
    public static LogScale ofToken(String token) {
        for (LogScale s : values()) if (s.token.equals(token)) return s;
        return LN;
    }
}
