package qupath.ext.flowpath.model;

/** Who set a cell's committed class. */
public enum Provenance {
    MODEL, MANUAL;
    public static Provenance fromCode(double code) { return code == 1.0 ? MANUAL : MODEL; }
}
