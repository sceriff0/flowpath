package qupath.ext.flowpath.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * One line describing a throwable for an error dialog or notification, never blank.
 * <p>
 * The pane passed {@code getMessage()} straight through, which is null for many failures a
 * background read or export can hit (an {@code OutOfMemoryError}, a wrapper carrying only a
 * cause), so the dialog body came up empty or read "null". A message is shown as it is;
 * without one, the class is named, followed by the cause's description when there is one.
 */
final class ErrorMessages {

    private ErrorMessages() {}

    /**
     * Walks the cause chain, collecting one segment per throwable: its message when it has
     * one (which ends the walk there, exactly as the single-throwable case would), otherwise
     * its class name before moving on to its cause. An identity-keyed visited set bounds the
     * walk -- a bare {@code cause == error} guard only ever caught an immediate two-throwable
     * self-loop; a longer cycle (A's cause is B, B's is C, C's is A again) would still recurse
     * forever without one.
     */
    static String describe(Throwable error) {
        if (error == null) return "Unknown error";
        List<String> segments = new ArrayList<>();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = error;
        while (current != null && visited.add(current)) {
            String message = current.getMessage();
            if (message != null && !message.isBlank()) {
                segments.add(message);
                break;
            }
            String name = current.getClass().getSimpleName();
            if (name.isEmpty()) name = current.getClass().getName();
            segments.add(name);
            current = current.getCause();
        }
        return String.join(": ", segments);
    }
}
