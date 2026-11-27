package com.dlsc.animatorfx.editor.ui;

import javafx.stage.Window;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The questions and messages of the editor. The application uses {@link SystemDialogs}; tests
 * answer them directly.
 *
 * <p>The file choosers are those of the platform and return when the user has chosen. The
 * others show inside the main window and do not wait: a question returns a future, which is
 * completed on the JavaFX application thread when the user answers. Only one of them shows at
 * a time. A question that is asked while another dialog is showing is not shown; its future is
 * completed at once with the answer that changes nothing ({@link CloseChoice#CANCEL},
 * {@code false}). A message that is requested meanwhile is dropped.
 */
public interface Dialogs {

    enum CloseChoice {SAVE, DISCARD, CANCEL}
    enum AudioPlacement {BEGINNING, CURSOR, CANCEL}

    /**
     * Asks where dropped audio should start. Implementations without this question cancel.
     *
     * @param cursorTime the cursor position captured when the files were dropped
     */
    default CompletableFuture<AudioPlacement> chooseAudioInsertion(double cursorTime) {
        return CompletableFuture.completedFuture(AudioPlacement.CANCEL);
    }

    /**
     * @return the chosen {@code .afx} file; empty when cancelled
     */
    Optional<Path> chooseOpenFile(Window owner);

    /**
     * @return the file to save to; empty when cancelled
     */
    Optional<Path> chooseSaveFile(Window owner, String suggestedName);

    /**
     * Asks what to do with the unsaved changes of a document that is about to close.
     */
    CompletableFuture<CloseChoice> confirmClose(String documentTitle);

    /**
     * Asks whether the unsaved changes may be thrown away.
     */
    CompletableFuture<Boolean> confirmRevert(String documentTitle);

    void showAbout();

    /**
     * Lists the warnings of an import, one per line.
     *
     * @param title what the warnings are about
     */
    void showWarnings(String title, List<String> lines);
}
