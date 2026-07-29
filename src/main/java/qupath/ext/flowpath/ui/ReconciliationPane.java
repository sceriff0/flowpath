package qupath.ext.flowpath.ui;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import qupath.ext.flowpath.engine.ReconciliationController;
import qupath.ext.flowpath.model.Candidate;
import qupath.ext.flowpath.model.CellPhenotype;
import qupath.ext.flowpath.model.PhenotypeTree;
import qupath.ext.flowpath.model.ReconciliationItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Worklist + per-cell candidate panel driving viewer-navigated point-and-click reconciliation. */
public class ReconciliationPane extends BorderPane {

    private final PhenotypeTree tree;
    private final ReconciliationController controller;
    private final Label header = new Label("No cells to reconcile");
    private final ListView<String> candidateList = new ListView<>();
    private final List<CellPhenotype> items = new ArrayList<>();
    private int cursor = -1;
    private Consumer<ReconciliationItem> onNavigate;

    public ReconciliationPane(PhenotypeTree tree, ReconciliationController controller) {
        this.tree = tree;
        this.controller = controller;
        Button commit = new Button("Commit selected");
        commit.setOnAction(e -> {
            String sel = candidateList.getSelectionModel().getSelectedItem();
            if (sel != null) commitCurrent(sel.split(" ")[0]);
        });
        Button skip = new Button("Skip");
        skip.setOnAction(e -> advance());
        setTop(header);
        setCenter(candidateList);
        setBottom(new VBox(commit, skip));
    }

    public void setOnNavigate(Consumer<ReconciliationItem> navigator) { this.onNavigate = navigator; }

    public void setItems(List<CellPhenotype> worklist) {
        items.clear();
        items.addAll(worklist);
        cursor = items.isEmpty() ? -1 : 0;
        showCurrent();
    }

    public CellPhenotype getCurrent() { return getCurrentOrNull(); }

    public CellPhenotype getCurrentOrNull() { return cursor >= 0 && cursor < items.size() ? items.get(cursor) : null; }

    public void commitCurrent(String phenotypeName) {
        if (cursor < 0 || cursor >= items.size()) return;
        controller.commit(items.get(cursor), phenotypeName);
        advance();
    }

    private void advance() {
        if (cursor < items.size() - 1) { cursor++; showCurrent(); }
        else { cursor = -1; showCurrent(); }
    }

    private void showCurrent() {
        CellPhenotype cell = getCurrentOrNull();
        if (cell == null) { header.setText("No cells to reconcile"); candidateList.getItems().clear(); return; }
        ReconciliationItem item = ReconciliationItem.of(cell, tree);
        header.setText("Cell " + cell.getLabel() + " — " + cell.getOutcome()
                + (item.constraintLabel().equals("—") ? "" : " [" + item.constraintLabel() + "]"));
        candidateList.getItems().clear();
        for (Candidate c : item.candidates()) {
            candidateList.getItems().add(c.name() + " " + String.format(Locale.US, "%.2f", c.score()));
        }
        if (onNavigate != null) onNavigate.accept(item);
    }
}
