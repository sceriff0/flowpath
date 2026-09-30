package qupath.ext.flowpath.engine;

import qupath.ext.flowpath.model.cohort.Alignment;

/** Where {@link TreeResolver} gets a slide's alignment for one column; null when none is known. */
@FunctionalInterface
public interface AlignmentLookup {

    Alignment alignment(String slideId, String columnKey);

    AlignmentLookup NONE = (slideId, columnKey) -> null;
}
