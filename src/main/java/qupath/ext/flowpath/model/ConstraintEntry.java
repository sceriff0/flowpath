package qupath.ext.flowpath.model;

import java.util.List;

/** One row of the sidecar constraint table (kind ∈ never|rare|soft|requires). */
public record ConstraintEntry(int id, List<String> markers, String kind, double rate) {}
