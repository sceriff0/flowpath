package qupath.ext.flowpath.ui;

import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import qupath.ext.flowpath.model.PhenotypeOutcome;
import qupath.ext.flowpath.model.PhenotypeStats;

/** Renders PhenotypeStats + reconciliation progress (spec §7.4). */
public class PhenotypeStatsPane extends VBox {

    private final Label summary = new Label();
    private final Label progress = new Label();

    public PhenotypeStatsPane() {
        getChildren().addAll(summary, progress);
    }

    public void update(PhenotypeStats stats) {
        StringBuilder sb = new StringBuilder("Total: ").append(stats.total()).append('\n');
        for (PhenotypeOutcome o : PhenotypeOutcome.values()) {
            if (o == PhenotypeOutcome.PHENOTYPE) continue;
            sb.append(o.reservedName()).append(": ").append(stats.count(o))
              .append(" (").append(ratePercent(stats, o)).append(")\n");
        }
        summary.setText(sb.toString().trim());
        progress.setText(progressText(stats.uncertainResolved(), stats.uncertainTotal()));
    }

    /** Toolkit-free formatter for the reconciliation-progress line. */
    public static String progressText(int resolved, int total) {
        return resolved + " of " + total + " Uncertain resolved";
    }

    /** Toolkit-free rate formatter for one outcome. */
    public static String ratePercent(PhenotypeStats stats, PhenotypeOutcome o) {
        return String.format(java.util.Locale.US, "%.1f%%", stats.rate(o) * 100.0);
    }
}
