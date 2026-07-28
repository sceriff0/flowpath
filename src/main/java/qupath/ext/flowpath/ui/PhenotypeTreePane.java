package qupath.ext.flowpath.ui;

import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import qupath.ext.flowpath.model.CellPhenotype;
import qupath.ext.flowpath.model.PhenotypeFilter;
import qupath.ext.flowpath.model.PhenotypeNode;
import qupath.ext.flowpath.model.PhenotypeTree;

import java.util.function.Consumer;
import java.util.function.Predicate;

/** TreeView of the phenotype hierarchy + reserved buckets; selection filters the viewer. */
public class PhenotypeTreePane extends TreeView<String> {

    private final PhenotypeTree tree;
    private Consumer<Predicate<CellPhenotype>> onSelected;

    public PhenotypeTreePane(PhenotypeTree tree) {
        this.tree = tree;
        TreeItem<String> root = new TreeItem<>("Phenotypes");
        root.setExpanded(true);
        for (PhenotypeNode n : tree.getRoots()) root.getChildren().add(buildItem(n));

        TreeItem<String> uncertain = new TreeItem<>("Uncertain");
        uncertain.getChildren().add(new TreeItem<>("Ambiguous"));
        uncertain.getChildren().add(new TreeItem<>("Conflict"));
        root.getChildren().add(uncertain);
        root.getChildren().add(new TreeItem<>("Artefact"));
        root.getChildren().add(new TreeItem<>("Unclassified"));

        setRoot(root);
        setShowRoot(false);

        getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            if (sel == null || onSelected == null) return;
            String base = sel.getValue().split(" \\(")[0];
            Predicate<CellPhenotype> p;
            if (tree.findByName(base) != null) p = PhenotypeFilter.forNode(base, tree);
            else p = PhenotypeFilter.forReserved(base);
            onSelected.accept(p);
        });
    }

    private TreeItem<String> buildItem(PhenotypeNode node) {
        TreeItem<String> item = new TreeItem<>(node.getName());
        for (PhenotypeNode c : node.getChildren()) item.getChildren().add(buildItem(c));
        return item;
    }

    public void setOnPhenotypeSelected(Consumer<Predicate<CellPhenotype>> handler) { this.onSelected = handler; }

    /** Re-label nodes with live counts; call after {@code tree.recomputeCounts(...)}. */
    public void refreshCounts() {
        relabel(getRoot());
        refresh();
    }

    private void relabel(TreeItem<String> item) {
        String base = item.getValue().split(" \\(")[0];
        PhenotypeNode node = tree.findByName(base);
        if (node != null) item.setValue(base + " (" + node.getCount() + ")");
        else if (isReserved(base)) item.setValue(base + " (" + tree.reservedCount(base) + ")");
        for (TreeItem<String> c : item.getChildren()) relabel(c);
    }

    private boolean isReserved(String s) {
        return s.equals("Ambiguous") || s.equals("Conflict") || s.equals("Artefact") || s.equals("Unclassified");
    }
}
