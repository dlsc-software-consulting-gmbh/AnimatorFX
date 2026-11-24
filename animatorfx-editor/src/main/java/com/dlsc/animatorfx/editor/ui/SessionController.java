package com.dlsc.animatorfx.editor.ui;

import java.io.UncheckedIOException;
import java.util.LinkedHashSet;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javafx.application.Platform;
import com.dlsc.animatorfx.editor.doc.EditorDocument;
import com.dlsc.animatorfx.editor.doc.ViewState;
import com.dlsc.animatorfx.editor.session.RecoveryStore;
import com.dlsc.animatorfx.editor.session.RecoveryStore.Recovered;
import com.dlsc.animatorfx.editor.session.RecoveryStore.Unreadable;
import com.dlsc.animatorfx.editor.session.SessionState;
import com.dlsc.animatorfx.editor.session.SessionState.DocumentState;
import com.dlsc.animatorfx.editor.session.SessionState.WindowState;
import com.dlsc.animatorfx.editor.session.SessionStore;
import com.dlsc.animatorfx.editor.ui.WindowPlacement.Placement;
import com.dlsc.animatorfx.model.io.AnimationFile;
import com.dlsc.animatorfx.model.io.AnimationFormatException;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.beans.InvalidationListener;
import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keeps the session state and the recovery copies of a window up to date: restores them on
 * start, writes {@code session.json} 500 ms after the last change and on shutdown, and stores a
 * recovery copy of every dirty document every 5 seconds (on a background thread) and on shutdown. A document that becomes clean or is
 * closed loses its recovery copy. A recovery copy that cannot be read on start is kept under
 * another name and reported.
 */
public final class SessionController {

    private static final System.Logger LOG = System.getLogger(SessionController.class.getName());
    private static final Duration SAVE_DELAY = Duration.millis(500);
    private static final Duration AUTOSAVE_INTERVAL = Duration.seconds(5);

    private final MainWindow window;
    private final Workspace workspace;
    private final SessionStore sessionStore;
    private final RecoveryStore recoveryStore;

    private final PauseTransition saveTimer = new PauseTransition(SAVE_DELAY);
    private final Timeline autosaveTimer = new Timeline(new KeyFrame(AUTOSAVE_INTERVAL, e -> autosaveInBackground()));
    private final InvalidationListener changed = obs -> scheduleSave();
    /** For properties: unlike an invalidation listener it is told about every change, read or not. */
    private final ChangeListener<Object> valueChanged = (obs, oldValue, newValue) -> scheduleSave();
    private final ChangeListener<Boolean> dirtyChanged = (obs, wasDirty, isDirty) -> dirtyChanged();
    /** The content that the recovery copy of a document holds, by document id. */
    private final Map<String, AnimationFile> stored = new HashMap<>();
    private final Map<String, LocalTime> autosavedAt = new HashMap<>();
    /** The content that could not be read back as a recovery copy, by document id; it is not tried again. */
    private final Map<String, AnimationFile> unstorable = new HashMap<>();
    /**
     * The content whose background copy failed with an {@code Error}, by document id: the timer
     * leaves it alone, {@link #autosaveNow()} and quitting try it again.
     */
    private final Map<String, AnimationFile> failedOnce = new HashMap<>();
    /** The documents whose failing recovery copy the user has been told about. */
    private final Set<String> reportedStoreFailures = new HashSet<>();
    /** The documents whose "recovered" notice still offers to discard them. */
    private final Map<EditorDocument, RecoveryNotice> recoveryNotices = new HashMap<>();

    private record RecoveryNotice(Runnable dismiss, ChangeListener<AnimationFile> contentListener) {
    }

    /** One file operation for the writer thread: storing a copy of a content, or removing the copy. */
    private static final class RecoveryJob {

        final String title;
        final String id;
        final AnimationFile content;
        final Path path;
        /** The generation of the document when the job was made, see {@link #generations}. */
        final int generation;
        final boolean store;
        final CompletableFuture<Void> done = new CompletableFuture<>();
        /** Written by the writer thread before the job is put into {@link #results}. */
        volatile Throwable failure;

        RecoveryJob(String title, String id, AnimationFile content, Path path, int generation, boolean store) {
            this.title = title;
            this.id = id;
            this.content = content;
            this.path = path;
            this.generation = generation;
            this.store = store;
        }
    }

    private static final long DRAIN_SECONDS = 10;

    /** Writes and removes the recovery copies of this window, one after the other, in the order asked for. */
    private final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "animatorfx-autosave");
        thread.setDaemon(true);
        return thread;
    });
    /** What the writer thread has done and the JavaFX thread has not taken note of yet. */
    private final Queue<RecoveryJob> results = new ConcurrentLinkedQueue<>();
    /** The jobs handed to the writer thread whose results have not been taken note of. */
    private final Set<RecoveryJob> pending = new LinkedHashSet<>();
    /** The content whose copy is being written, by document id: it is not handed over twice. */
    private final Map<String, AnimationFile> inFlight = new HashMap<>();
    /** Goes up whenever the copy of a document is given up; results of older jobs are dropped. */
    private final Map<String, Integer> generations = new HashMap<>();
    private WindowState normalBounds;
    private boolean closed;

    SessionController(MainWindow window, SessionStore sessionStore, RecoveryStore recoveryStore) {
        this.window = window;
        this.workspace = window.getWorkspace();
        this.sessionStore = sessionStore;
        this.recoveryStore = recoveryStore;
        saveTimer.setOnFinished(e -> saveNow());
        autosaveTimer.setCycleCount(Animation.INDEFINITE);
    }

    /**
     * Applies the stored session to the window and reopens its documents; must run before the
     * window shows. Never fails because of the stored state.
     */
    void restore() {
        SessionState state = sessionStore.load();
        window.getTheme().setMode(Theme.parseMode(state.theme(), Theme.Mode.SYSTEM));
        placeWindow(state.window());
        window.applyLayout(state.layout());
        window.getToolBar().setMuted(state.audioMuted());
        window.getTimelineView().setFollowSelection(state.followSelection());
        workspace.getRecentFiles().setAll(state.recentFiles());

        Set<String> seen = new HashSet<>();
        EditorDocument active = null;
        for (DocumentState saved : state.documents()) {
            if (!seen.add(saved.id())) {
                continue;
            }
            EditorDocument document = reopen(saved);
            if (document != null) {
                apply(saved, document);
                adopt(document);
                if (saved.id().equals(state.activeDocumentId())) {
                    active = document;
                }
            }
        }
        // copies the session does not know about are left over from a crash
        for (Recovered recovered : recoveryStore.all()) {
            if (seen.add(recovered.docId())) {
                adopt(recovered(recovered, recovered.originalPath()));
            }
        }
        for (Unreadable copy : recoveryStore.takeUnreadable()) {
            String name = copy.originalPath() == null || copy.originalPath().getFileName() == null
                    ? "an unsaved document" : copy.originalPath().getFileName().toString();
            window.getNoticeBar().show("The recovery copy of " + name + " could not be read. It was kept as "
                    + copy.movedTo() + ".");
        }
        if (workspace.getDocuments().isEmpty()) {
            workspace.add(EditorDocument.untitled());
        } else if (active != null) {
            workspace.setActiveDocument(active);
        }
    }

    /**
     * Starts following the window: from now on changes are saved.
     */
    void start() {
        Stage stage = window.getStage();
        ChangeListener<Object> bounds = (obs, oldValue, newValue) -> {
            rememberBounds();
            scheduleSave();
        };
        stage.xProperty().addListener(bounds);
        stage.yProperty().addListener(bounds);
        stage.widthProperty().addListener(bounds);
        stage.heightProperty().addListener(bounds);
        stage.maximizedProperty().addListener(valueChanged);
        window.getTheme().modeProperty().addListener(valueChanged);
        window.getToolBar().mutedProperty().addListener(valueChanged);
        window.getTimelineView().followSelectionProperty().addListener(valueChanged);
        window.setOnLayoutChanged(this::scheduleSave);
        workspace.getRecentFiles().addListener(changed);
        workspace.activeDocumentProperty().addListener((obs, oldDocument, newDocument) -> {
            scheduleSave();
            updateStatus();
        });
        workspace.getDocuments().addListener((ListChangeListener<EditorDocument>) change -> {
            while (change.next()) {
                change.getRemoved().forEach(this::untrack);
                change.getAddedSubList().forEach(this::track);
            }
            scheduleSave();
        });
        workspace.getDocuments().forEach(this::track);
        autosaveTimer.play();
        updateStatus();
        // restoring may have dropped documents; the stored session should not keep them until the next change
        scheduleSave();
    }

    void showWindow() {
        Stage stage = window.getStage();
        if (!stage.isShowing()) {
            // Lay out the header before the native peer is created: changing its button height
            // during peer initialization can overwrite the window's explicitly restored size.
            var root = window.getScene().getRoot();
            root.applyCss();
            root.resize(normalBounds.width(), normalBounds.height());
            root.layout();
        }
        stage.show();
    }

    /**
     * Stops the timer that stores recovery copies every five seconds; they are then only stored
     * when asked for, and when the window closes. For tests that decide when a copy is written.
     */
    void stopAutosaveTimer() {
        autosaveTimer.stop();
    }

    /**
     * Writes {@code session.json} now instead of after the delay.
     */
    public void saveNow() {
        saveTimer.stop();
        try {
            sessionStore.save(snapshot());
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not save the session state", e);
        }
    }

    // ------------------------------------------------------------------ recovery copies
    //
    // The writer thread only touches files: it stores a copy or removes one, in the order the
    // JavaFX thread asked for, and hands back what happened. Everything that is remembered about
    // the copies (what is on disk, what failed, what the user was told) is kept on the JavaFX
    // thread. A document has a generation that goes up whenever its copy is given up (saved,
    // reverted, closed); a result from an older generation is not remembered, and the removal
    // that was queued behind it has taken its file away.

    /**
     * Stores a recovery copy of every dirty document that changed since its last copy, now, on
     * the calling thread, after everything that was handed to the writer thread has been done.
     * A document whose copy could not be read back keeps its previous copy; the user is told
     * once per document, and the same content is not tried again. Returns {@code false} when the
     * writer could not be drained and the latest copies were not safely written.
     */
    public boolean autosaveNow() {
        if (!drainWriter()) {
            LOG.log(System.Logger.Level.WARNING, "The recovery writer is stuck; no recovery copies were written");
            window.getNoticeBar().show("The recovery writer did not finish; the window will stay open.");
            return false;
        }
        // a write that failed in the background may work now: only what the reader rejects is left out
        failedOnce.clear();
        for (EditorDocument document : workspace.getDocuments()) {
            RecoveryJob job = jobFor(document);
            if (job != null) {
                job.failure = attempt(() -> recoveryStore.store(job.id, job.content, job.path));
                noted(job);
            }
        }
        updateStatus();
        return true;
    }

    /**
     * What the timer does: like {@link #autosaveNow()}, but the copies are written on a
     * background thread, because serialising, reading back and writing a document with a large
     * sound takes a few hundred milliseconds. The results are taken note of on the JavaFX
     * application thread.
     *
     * @return see {@link #whenRecoveryIdle()}
     */
    public CompletableFuture<Void> autosaveInBackground() {
        if (!closed) {
            for (EditorDocument document : workspace.getDocuments()) {
                RecoveryJob job = jobFor(document);
                if (job != null && inFlight.get(job.id) != job.content) {
                    inFlight.put(job.id, job.content);
                    submit(job, () -> recoveryStore.store(job.id, job.content, job.path));
                }
            }
        }
        updateStatus();
        return whenRecoveryIdle();
    }

    /**
     * Completes, on the JavaFX application thread, when every recovery copy and every removal
     * that has been handed to the writer thread so far is done and taken note of; done already
     * when there is none.
     */
    public CompletableFuture<Void> whenRecoveryIdle() {
        return CompletableFuture.allOf(pending.stream().map(job -> job.done).toArray(CompletableFuture[]::new));
    }

    /** The copy to write for a document; {@code null} when it needs none. */
    private RecoveryJob jobFor(EditorDocument document) {
        String id = document.getId();
        AnimationFile content = document.getFileContent();
        if (!document.isDirty() || stored.get(id) == content || unstorable.get(id) == content
                || failedOnce.get(id) == content) {
            return null;
        }
        return new RecoveryJob(document.getTitle(), id, content, document.getPath(), generation(id), true);
    }

    private int generation(String id) {
        return generations.getOrDefault(id, 0);
    }

    /** Hands a file operation to the writer thread. Whatever it throws, the job completes. */
    private void submit(RecoveryJob job, Runnable operation) {
        pending.add(job);
        try {
            writer.execute(() -> {
                try {
                    job.failure = attempt(operation);
                } finally {
                    results.add(job);
                    Platform.runLater(this::noteResults);
                }
            });
        } catch (RuntimeException e) {
            // the writer has been shut down: nothing was done
            job.failure = e;
            results.add(job);
            noteResults();
        }
    }

    /** Runs a file operation and returns what went wrong, an {@code Error} included, or {@code null}. */
    private static Throwable attempt(Runnable operation) {
        try {
            operation.run();
            return null;
        } catch (Throwable e) {
            return e;
        }
    }

    /** Takes note of what the writer thread has done; on the JavaFX application thread. */
    private void noteResults() {
        RecoveryJob job;
        while ((job = results.poll()) != null) {
            pending.remove(job);
            if (job.store) {
                inFlight.remove(job.id, job.content);
                noted(job);
            } else if (job.failure != null) {
                LOG.log(System.Logger.Level.WARNING, "Could not delete the recovery copy of " + job.title, job.failure);
            }
            job.done.complete(null);
        }
        updateStatus();
    }

    /** Remembers a written copy, or reports a failed one; on the JavaFX application thread. */
    private void noted(RecoveryJob job) {
        String id = job.id;
        if (job.generation != generation(id)) {
            // saved or closed since: the copy is not the document's any more, and the removal
            // that followed it on the writer thread has deleted it
            return;
        }
        Throwable failure = job.failure;
        if (failure == null) {
            stored.put(id, job.content);
            unstorable.remove(id);
            failedOnce.remove(id);
            autosavedAt.put(id, LocalTime.now());
            return;
        }
        Throwable cause = failure instanceof UncheckedIOException && failure.getCause() != null ? failure.getCause() : failure;
        if (cause instanceof AnimationFormatException) {
            // the reader rejects this content, now and always
            unstorable.put(id, job.content);
        } else if (failure instanceof Error) {
            // out of memory, most likely: not again every five seconds, but once more when quitting
            failedOnce.put(id, job.content);
        }
        if (reportedStoreFailures.add(id)) {
            LOG.log(System.Logger.Level.WARNING, "Could not store a recovery copy of " + job.title, failure);
            String reason = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
            window.getNoticeBar().show("Recovery copy could not be written: " + reason + " (" + job.title + ")");
        }
    }

    /**
     * Waits, at most {@link #DRAIN_SECONDS} seconds, until the writer thread has done everything
     * it was handed, and takes note of the results on the calling thread, which is the JavaFX
     * application thread. Only quitting and an explicit {@link #autosaveNow()} wait like this.
     *
     * @return whether the writer thread is idle now
     */
    private boolean drainWriter() {
        if (!pending.isEmpty()) {
            CountDownLatch idle = new CountDownLatch(1);
            try {
                writer.execute(idle::countDown);
                if (!idle.await(DRAIN_SECONDS, TimeUnit.SECONDS)) {
                    return false;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (RuntimeException e) {
                return false;
            }
        }
        noteResults();
        return true;
    }

    /**
     * Persists everything for the next start: recovery copies of the latest content of the dirty
     * documents and the session state. Copies of documents that were saved or closed are gone by
     * then, also when their removal was still waiting behind a write. A failed drain leaves the
     * controller open and returns {@code false}; a completed shutdown returns {@code true}.
     */
    boolean shutdown() {
        if (closed) {
            return true;
        }
        if (!autosaveNow()) {
            return false;
        }
        autosaveTimer.stop();
        closed = true;
        writer.shutdown();
        saveNow();
        return true;
    }

    SessionState snapshot() {
        List<DocumentState> documents = new ArrayList<>();
        for (EditorDocument document : workspace.getDocuments()) {
            ViewState view = document.getViewState();
            documents.add(new DocumentState(document.getId(), document.getPath(), view.getCanvasZoom(),
                    view.getCanvasPanX(), view.getCanvasPanY(), document.getPlayhead(),
                    document.getSelection().layerIds(), List.copyOf(document.getSelection().keyframes()),
                    view.getTimelineZoom(), view.getTimelineScroll(), List.copyOf(view.getExpandedLayerIds()),
                    document.isAutoKey(), view.isShowAllProperties()));
        }
        EditorDocument active = workspace.getActiveDocument();
        WindowState bounds = normalBounds == null ? null : new WindowState(normalBounds.x(), normalBounds.y(),
                normalBounds.width(), normalBounds.height(), window.getStage().isMaximized(), screenIndex());
        return new SessionState(bounds, window.getLayoutState(), window.getTheme().getMode().name(), documents,
                active == null ? null : active.getId(), workspace.getRecentFiles(), window.getToolBar().isMuted(),
                window.getTimelineView().isFollowSelection());
    }

    private void scheduleSave() {
        if (!closed) {
            saveTimer.playFromStart();
        }
    }

    private void placeWindow(WindowState saved) {
        List<Rectangle2D> screens = new ArrayList<>();
        screens.add(Screen.getPrimary().getVisualBounds());
        for (Screen screen : Screen.getScreens()) {
            if (!screen.equals(Screen.getPrimary())) {
                screens.add(screen.getVisualBounds());
            }
        }
        Placement placement = WindowPlacement.resolve(saved, screens);
        Stage stage = window.getStage();
        stage.setX(placement.x());
        stage.setY(placement.y());
        stage.setWidth(placement.width());
        stage.setHeight(placement.height());
        normalBounds = new WindowState(placement.x(), placement.y(), placement.width(), placement.height(), false, 0);
        stage.setMaximized(placement.maximized());
    }

    /** The bounds to come back to are those of the window while it is not maximized. */
    private void rememberBounds() {
        Stage stage = window.getStage();
        if (stage.isMaximized() || stage.isFullScreen() || stage.isIconified()
                || Double.isNaN(stage.getX()) || Double.isNaN(stage.getWidth())) {
            return;
        }
        normalBounds = new WindowState(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight(), false, 0);
    }

    private int screenIndex() {
        Stage stage = window.getStage();
        if (Double.isNaN(stage.getX()) || Double.isNaN(stage.getWidth())) {
            return 0;
        }
        List<Screen> on = Screen.getScreensForRectangle(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
        return on.isEmpty() ? 0 : Math.max(0, Screen.getScreens().indexOf(on.get(0)));
    }

    /** The document of a session entry, or {@code null} (with a notice) when it cannot come back. */
    private EditorDocument reopen(DocumentState saved) {
        Recovered copy = recoveryStore.load(saved.id()).orElse(null);
        if (copy != null) {
            return recovered(copy, saved.path() != null ? saved.path() : copy.originalPath());
        }
        Path file = saved.path();
        if (file == null) {
            return EditorDocument.untitled(saved.id());
        }
        if (!Files.exists(file)) {
            window.getNoticeBar().show(file.getFileName() + " no longer exists and was removed from the session.");
            return null;
        }
        try {
            return EditorDocument.open(saved.id(), file);
        } catch (IOException | RuntimeException e) {
            window.getNoticeBar().show("Could not reopen " + file.getFileName() + ": " + e.getMessage());
            return null;
        }
    }

    private EditorDocument recovered(Recovered copy, Path file) {
        EditorDocument document = EditorDocument.recovered(copy.docId(), copy.file(), file);
        stored.put(document.getId(), document.getFileContent());
        Runnable dismiss = window.getNoticeBar().show("Unsaved changes to " + document.getTitle() + " were recovered.",
                "Discard recovered changes", () -> discardRecovered(document));
        // Discarding is only offered for the recovered content itself: once the document is edited,
        // saved or closed, the offer would throw away more than the recovered changes.
        ChangeListener<AnimationFile> contentListener = (obs, oldContent, newContent) -> retireRecoveryNotice(document);
        document.fileContentProperty().addListener(contentListener);
        recoveryNotices.put(document, new RecoveryNotice(dismiss, contentListener));
        return document;
    }

    private void retireRecoveryNotice(EditorDocument document) {
        RecoveryNotice notice = recoveryNotices.remove(document);
        if (notice != null) {
            document.fileContentProperty().removeListener(notice.contentListener());
            notice.dismiss().run();
        }
    }

    private void discardRecovered(EditorDocument document) {
        if (recoveryNotices.get(document) == null || !workspace.getDocuments().contains(document)) {
            return;
        }
        retireRecoveryNotice(document);
        Path file = document.getPath();
        if (file != null && Files.exists(file)) {
            try {
                document.revert();
                return;
            } catch (IOException | RuntimeException e) {
                window.getNoticeBar().show("Could not revert " + document.getTitle() + ": " + e.getMessage());
            }
        }
        window.getFileActions().discard(document);
    }

    private static void apply(DocumentState saved, EditorDocument document) {
        ViewState view = document.getViewState();
        view.setCanvasZoom(saved.canvasZoom() > 0 ? saved.canvasZoom() : 1);
        view.setCanvasPanX(saved.canvasPanX());
        view.setCanvasPanY(saved.canvasPanY());
        view.setTimelineZoom(saved.timelineZoom() > 0 ? saved.timelineZoom() : 1);
        view.setTimelineScroll(saved.timelineScroll());
        view.getExpandedLayerIds().addAll(saved.expandedLayerIds());
        view.setShowAllProperties(saved.showAllProperties());
        document.setPlayhead(saved.playhead());
        document.setAutoKey(saved.autoKey());
        document.getSelection().layerIds().setAll(saved.selectedLayerIds());
        document.getSelection().keyframes().addAll(saved.selectedKeyframes());
        document.pruneSelection();
    }

    /** Adds a restored document without touching the recent files order more than needed. */
    private void adopt(EditorDocument document) {
        List<Path> recent = List.copyOf(workspace.getRecentFiles());
        workspace.add(document);
        workspace.getRecentFiles().setAll(recent);
    }

    private void track(EditorDocument document) {
        ViewState view = document.getViewState();
        document.pathProperty().addListener(valueChanged);
        document.playheadProperty().addListener(valueChanged);
        document.autoKeyProperty().addListener(valueChanged);
        document.getSelection().layerIds().addListener(changed);
        document.getSelection().keyframes().addListener(changed);
        view.canvasZoomProperty().addListener(valueChanged);
        view.canvasPanXProperty().addListener(valueChanged);
        view.canvasPanYProperty().addListener(valueChanged);
        view.timelineZoomProperty().addListener(valueChanged);
        view.timelineScrollProperty().addListener(valueChanged);
        view.showAllPropertiesProperty().addListener(valueChanged);
        view.getExpandedLayerIds().addListener(changed);
        document.dirtyProperty().addListener(dirtyChanged);
    }

    private void untrack(EditorDocument document) {
        ViewState view = document.getViewState();
        document.pathProperty().removeListener(valueChanged);
        document.playheadProperty().removeListener(valueChanged);
        document.autoKeyProperty().removeListener(valueChanged);
        document.getSelection().layerIds().removeListener(changed);
        document.getSelection().keyframes().removeListener(changed);
        view.canvasZoomProperty().removeListener(valueChanged);
        view.canvasPanXProperty().removeListener(valueChanged);
        view.canvasPanYProperty().removeListener(valueChanged);
        view.timelineZoomProperty().removeListener(valueChanged);
        view.timelineScrollProperty().removeListener(valueChanged);
        view.showAllPropertiesProperty().removeListener(valueChanged);
        view.getExpandedLayerIds().removeListener(changed);
        document.dirtyProperty().removeListener(dirtyChanged);
        retireRecoveryNotice(document);
        forget(document);
    }

    private void dirtyChanged() {
        for (EditorDocument document : workspace.getDocuments()) {
            if (!document.isDirty()) {
                retireRecoveryNotice(document);
                forget(document);
            }
        }
        updateStatus();
    }

    /**
     * Gives the recovery copy of a document up, also when this window did not write it: a stale
     * copy would shadow the file on the next start. While the writer thread is busy the removal
     * is queued behind what it is doing, so that a copy that is being written right now is
     * removed after it has landed, and the JavaFX thread does not wait for it.
     */
    private void forget(EditorDocument document) {
        String id = document.getId();
        generations.merge(id, 1, Integer::sum);
        stored.remove(id);
        unstorable.remove(id);
        failedOnce.remove(id);
        inFlight.remove(id);
        reportedStoreFailures.remove(id);
        autosavedAt.remove(id);
        if (pending.isEmpty() || closed) {
            recoveryStore.discard(id);
        } else {
            submit(new RecoveryJob(document.getTitle(), id, null, null, generation(id), false),
                    () -> recoveryStore.discard(id));
        }
    }

    private void updateStatus() {
        EditorDocument active = workspace.getActiveDocument();
        window.getStatusBar().setAutosaveState(active == null ? ""
                : Formats.autosaveState(active.isDirty(), autosavedAt.get(active.getId())));
    }
}
