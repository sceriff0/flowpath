package qupath.ext.flowpath.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What an error dialog or notification says about a throwable. {@code getMessage()} alone is
 * null for many of the failures a background job can hit — an {@code OutOfMemoryError}, a
 * {@code NullPointerException} without helpful messages, a wrapper with only a cause — and the
 * dialogs showed an empty body (or "...detections: null").
 */
class ErrorMessagesTest {

    @Test
    void aMessageIsShownAsItIs() {
        assertEquals("/out.csv (Permission denied)",
                ErrorMessages.describe(new IOException("/out.csv (Permission denied)")));
    }

    @Test
    void noMessageNamesTheClass() {
        assertEquals("OutOfMemoryError", ErrorMessages.describe(new OutOfMemoryError()));
        assertEquals("NullPointerException", ErrorMessages.describe(new NullPointerException("  ")));
    }

    @Test
    void noMessageButACauseNamesBoth() {
        Throwable wrapped = new RuntimeException((String) null, new IOException("disk full"));
        assertEquals("RuntimeException: disk full", ErrorMessages.describe(wrapped));
        Throwable bare = new IllegalStateException((String) null, new OutOfMemoryError());
        assertEquals("IllegalStateException: OutOfMemoryError", ErrorMessages.describe(bare));
    }

    @Test
    void aSelfDescribingWrapperKeepsItsOwnMessage() {
        // new RuntimeException(cause) sets the message to cause.toString(): non-blank, kept.
        String described = ErrorMessages.describe(new UncheckedIOException(new IOException("gone")));
        assertFalse(described.isBlank());
        assertTrue(described.contains("gone"), described);
    }

    @Test
    void anAnonymousClassAndNullAreNeverBlank() {
        Throwable anonymous = new RuntimeException() { };
        assertFalse(ErrorMessages.describe(anonymous).isBlank());
        assertEquals("Unknown error", ErrorMessages.describe(null));
    }

    /**
     * A direct self-loop (cause == error) was already guarded; a longer cycle was not, and
     * would have recursed until the stack overflowed. Three messageless throwables whose
     * causes point around in a circle must still terminate, describing each exactly once.
     */
    @Test
    void aMultiLevelCauseCycleTerminatesInsteadOfRecursingForever() {
        RuntimeException a = new RuntimeException((String) null);
        RuntimeException b = new RuntimeException((String) null);
        RuntimeException c = new RuntimeException((String) null);
        a.initCause(b);
        b.initCause(c);
        c.initCause(a);

        assertEquals("RuntimeException: RuntimeException: RuntimeException",
                ErrorMessages.describe(a));
    }
}
