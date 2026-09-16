package qupath.ext.flowpath.ui;

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

    static String describe(Throwable error) {
        if (error == null) return "Unknown error";
        String message = error.getMessage();
        if (message != null && !message.isBlank()) return message;
        String name = error.getClass().getSimpleName();
        if (name.isEmpty()) name = error.getClass().getName();
        Throwable cause = error.getCause();
        return cause == null || cause == error ? name : name + ": " + describe(cause);
    }
}
