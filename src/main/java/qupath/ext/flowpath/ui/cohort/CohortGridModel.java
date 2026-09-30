package qupath.ext.flowpath.ui.cohort;

import qupath.ext.flowpath.cohort.AlignmentModel;
import qupath.ext.flowpath.cohort.CohortSession;
import qupath.ext.flowpath.cohort.CohortState;
import qupath.ext.flowpath.cohort.ReferenceRanking;
import qupath.ext.flowpath.cohort.ReviewItem;
import qupath.ext.flowpath.engine.TreeResolver;
import qupath.ext.flowpath.model.GateAxis;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.GateValues;
import qupath.ext.flowpath.model.GateWalk;
import qupath.ext.flowpath.model.Region2DGate;
import qupath.ext.flowpath.model.SlideSetting;
import qupath.ext.flowpath.model.cohort.Alignment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the Cohort window shows, derived — never set (CLAUDE.md "UI state is derived"). Pure: no
 * JavaFX. Rows are the project's slides in project order; columns the enabled gates in tree
 * order, keyed by value {@code (rootIndex, gatePath)}, never by a {@code GateNode} or a
 * {@code ReviewItem}'s gate (the review was scored on a deep copy of the tree).
 */
public record CohortGridModel(Banner banner, List<Column> columns, List<Row> rows, Detail detail) {

    public enum CellMark {
        OK("✓"), LOOK("⚠"), REVIEWED("↷"), ADJUSTED("✎"), SKIPPED("⊘"), NOT_MEASURED("—"),
        NOT_CORRECTED("="), NONE("");

        public final String glyph;

        CellMark(String glyph) { this.glyph = glyph; }
    }

    public enum RowStatus { READY, SAMPLING, FAILED, EXCLUDED }

    public record Column(int rootIndex, String gatePath, String header) {}

    public record Row(String slideId, String name, boolean reference, RowStatus status, String statusText,
                      int cells, List<CellMark> marks, int lookCount, boolean canExclude, boolean canBeReference) {
        public Row { marks = List.copyOf(marks); }
    }

    public record Banner(String headline, String suggestedId, String suggestedName, List<String> notes) {
        public Banner { notes = List.copyOf(notes); }
    }

    public record Detail(ReviewItem.Key key, String title, CellMark mark, List<String> reasons,
                         String referenceValue, String appliedValue) {
        public Detail { reasons = List.copyOf(reasons); }
    }

    public static final String SCOPE_NOTE = "correction assumes positive/negative markers, not graded intensity";

    public CohortGridModel {
        columns = List.copyOf(columns);
        rows = List.copyOf(rows);
    }

    public static CohortGridModel derive(CohortSession session, GateTree tree, ReviewItem.Key selected,
                                         boolean onlyLooks) {
        List<GateWalk.Entry> entries = GateWalk.enabled(tree);
        List<Column> columns = columns(entries);
        String reference = tree.getReferenceSlideId();
        Map<String, ReviewItem> items = new HashMap<>();
        for (ReviewItem i : session.review().items()) items.put(keyString(i.key()), i);

        List<Row> rows = new ArrayList<>();
        for (CohortSession.SlideSquare sq : session.slideStrip()) {
            RowStatus status = switch (sq.status()) {
                case EXCLUDED -> RowStatus.EXCLUDED;
                case FAILED -> RowStatus.FAILED;
                case SAMPLING -> RowStatus.SAMPLING;
                case READY, NEEDS_LOOK -> RowStatus.READY;
            };
            String statusText = switch (status) {
                case EXCLUDED -> "excluded";
                case FAILED -> "failed: " + sq.failure();
                case SAMPLING -> "sampling…";
                case READY -> "";
            };
            List<CellMark> marks = new ArrayList<>();
            int looks = 0;
            for (GateWalk.Entry e : entries) {
                CellMark mark = status != RowStatus.READY ? CellMark.NONE
                        : mark(session, e, sq.slideId(), reference, items);
                if (mark == CellMark.LOOK) looks++;
                marks.add(mark);
            }
            boolean isRef = sq.slideId().equals(reference);
            Row row = new Row(sq.slideId(), sq.name(), isRef, status, statusText, sq.cells(), marks, looks,
                    !isRef, status == RowStatus.READY && !isRef);
            if (!onlyLooks || looks > 0) rows.add(row);
        }
        return new CohortGridModel(banner(session, tree), columns, rows,
                detail(session, tree, entries, selected, items));
    }

    private static List<Column> columns(List<GateWalk.Entry> entries) {
        Map<String, Integer> perPath = new HashMap<>();
        for (GateWalk.Entry e : entries) perPath.merge(e.gatePath(), 1, Integer::sum);
        List<Column> out = new ArrayList<>();
        for (GateWalk.Entry e : entries) {
            String header = perPath.get(e.gatePath()) > 1
                    ? "#" + (e.rootIndex() + 1) + " " + e.gatePath() : e.gatePath();
            out.add(new Column(e.rootIndex(), e.gatePath(), header));
        }
        return out;
    }

    private static String keyString(ReviewItem.Key k) {
        return k.slideId() + "\u0000" + k.rootIndex() + "\u0000" + k.gatePath();
    }

    static CellMark mark(CohortSession session, GateWalk.Entry e, String slideId, String reference,
                         Map<String, ReviewItem> items) {
        GateNode gate = e.gate();
        SlideSetting setting = gate.slideSetting(slideId);
        if (setting instanceof SlideSetting.Skip) return CellMark.SKIPPED;
        if (setting instanceof SlideSetting.Manual) return CellMark.ADJUSTED;
        if (items.containsKey(keyString(new ReviewItem.Key(slideId, e.rootIndex(), e.gatePath())))) {
            return CellMark.LOOK;
        }
        // By value, never by ReviewItem's gate: the review was scored on a deep copy, so its
        // GateNodes are not the live tree's. The slide's own index says whether it carries the channel.
        var sample = session.sample(slideId);
        if (sample != null) {
            for (String ch : gate.getChannels()) {
                if (ch != null && !ch.isEmpty() && sample.index().getMarkerIndex(ch) < 0) {
                    return CellMark.NOT_MEASURED;
                }
            }
        }
        if (setting instanceof SlideSetting.Reviewed) return CellMark.REVIEWED;
        if (reference == null) return CellMark.NOT_CORRECTED;
        if (slideId.equals(reference)) return CellMark.OK;
        if (!gate.isCorrectStaining()) return CellMark.NOT_CORRECTED;
        List<String> channels = gate.getChannels();
        for (int k = 0; k < GateAxis.axisCount(gate) && k < channels.size(); k++) {
            String key = new AlignmentModel.ColumnRef(channels.get(k), gate.compartmentAt(k),
                    gate.statisticAt(k)).key();
            Alignment a = session.lookup().alignment(slideId, key);
            if (a == null || a.kind() == Alignment.Kind.IDENTITY) return CellMark.NOT_CORRECTED;
        }
        return CellMark.OK;
    }

    private static Banner banner(CohortSession session, GateTree tree) {
        CohortState state = session.state();
        ReferenceRanking.Result ranking = session.ranking();
        List<String> notes = new ArrayList<>();
        String suggestedId = session.suggestedReferenceId();
        String suggestedName = suggestedId == null ? null : session.slideName(suggestedId);
        String reference = tree.getReferenceSlideId();
        String headline;
        if (state.correctionDisabled() && state.message() != null) {
            headline = state.message();
        } else if (reference == null) {
            headline = "No reference slide — thresholds are not corrected between slides.";
            if (suggestedId != null) notes.add("Suggested: " + suggestedName + " — " + ranking.reason());
            else if (state.sampling()) notes.add("Ranking once sampling finishes");
            else if (ranking.eligibleCount() < ReferenceRanking.MIN_ELIGIBLE) {
                notes.add("With fewer than three eligible slides there is no suggestion; "
                        + "pick the slide you know best");
            }
            for (String c : ranking.uncorrectableColumns()) {
                notes.add(c + ": no slide has a clear negative peak — " + c + " is not corrected");
            }
        } else {
            String name = session.slideName(reference);
            int pos = ranking.position(reference);
            long of = ranking.eligibleCount();
            headline = "★ " + name + (pos == 1 ? String.format(Locale.US, " · most central of %d", of)
                    : pos > 1 ? String.format(Locale.US, " · ranks %d of %d", pos, of) : "");
            notes.addAll(ranking.notesFor(reference, session::slideName));
            if (suggestedId != null) notes.add("Suggested: " + suggestedName + " — " + ranking.reason());
        }
        notes.add(SCOPE_NOTE);
        return new Banner(headline, suggestedId, suggestedName, notes);
    }

    private static Detail detail(CohortSession session, GateTree tree, List<GateWalk.Entry> entries,
                                 ReviewItem.Key selected, Map<String, ReviewItem> items) {
        if (selected == null) return null;
        GateWalk.Entry entry = null;
        for (GateWalk.Entry e : entries) {
            if (e.rootIndex() == selected.rootIndex() && e.gatePath().equals(selected.gatePath())) entry = e;
        }
        if (entry == null) return null;
        String slideId = selected.slideId();
        ReviewItem item = items.get(keyString(selected));
        CellMark mark = mark(session, entry, slideId, tree.getReferenceSlideId(), items);
        TreeResolver.Applied applied = TreeResolver.resolve(tree, slideId, session.lookup()).applied(entry.gate());
        String ref = applied == null ? "" : format(entry.gate(), applied.reference());
        String app = applied == null ? "" : format(entry.gate(), applied.applied());
        String title = session.slideName(slideId) + " · " + entry.gatePath();
        return new Detail(selected, title, mark, item == null ? List.of() : item.reasons(), ref, app);
    }

    /** Each axis's values joined with ", ", the axes joined with " / "; a region gate has no single cut. */
    private static String format(GateNode gate, GateValues v) {
        if (gate instanceof Region2DGate) return "region";
        List<String> axes = new ArrayList<>();
        for (int k = 0; k < v.axisCount(); k++) {
            List<String> values = new ArrayList<>();
            for (double d : v.axis(k)) values.add(String.format(Locale.US, "%.4g", d));
            axes.add(String.join(", ", values));
        }
        return String.join(" / ", axes);
    }
}
