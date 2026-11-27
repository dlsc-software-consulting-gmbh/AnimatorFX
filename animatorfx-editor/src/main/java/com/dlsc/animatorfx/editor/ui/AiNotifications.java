package com.dlsc.animatorfx.editor.ui;

import com.dlsc.animatorfx.editor.ai.AiAttachment;
import com.dlsc.animatorfx.editor.ai.AiErrors;
import com.dlsc.animatorfx.editor.ai.AiProviderClient;
import com.dlsc.animatorfx.editor.ai.AiPreviewFrames;
import com.dlsc.animatorfx.editor.ai.AiSettings;
import com.dlsc.animatorfx.editor.ai.AiUsage;
import com.dlsc.animatorfx.editor.doc.EditorDocument;
import com.dlsc.animatorfx.model.io.AnimationFile;
import com.dlsc.gemsfx.infocenter.InfoCenterPane;
import com.dlsc.gemsfx.infocenter.Notification;
import com.dlsc.gemsfx.infocenter.NotificationGroup;
import com.dlsc.gemsfx.infocenter.NotificationView;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.image.Image;
import javafx.scene.control.Button;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Owns session-local generation jobs and their notification-center entries. */
final class AiNotifications {
    private final InfoCenterPane pane = new InfoCenterPane();
    private final Workspace workspace;
    private final Consumer<String> activity;
    private final ObservableList<Job> jobs = FXCollections.observableArrayList();
    private final ObservableList<Job> readOnlyJobs = FXCollections.unmodifiableObservableList(jobs);
    private final ReadOnlyBooleanWrapper running = new ReadOnlyBooleanWrapper(this, "running");
    private boolean disposed;

    AiNotifications(Workspace workspace, Consumer<String> activity) {
        this.workspace = workspace;
        this.activity = activity;
        pane.setAutoHideDuration(javafx.util.Duration.seconds(8));
        pane.getInfoCenterView().setTransparent(true);
        // GemsFX's default placeholder is one static Label shared by every InfoCenterView; reusing it
        // across windows drags stale CSS state into the new scene
        pane.getInfoCenterView().setPlaceholder(null);
        running.bind(Bindings.createBooleanBinding(
                () -> jobs.stream().anyMatch(job -> job.state == State.RUNNING), jobs));
    }

    /**
     * Whether at least one generation job is still running.
     *
     * @return the running property
     */
    final ReadOnlyBooleanProperty runningProperty() {
        return running.getReadOnlyProperty();
    }

    final boolean isRunning() {
        return running.get();
    }

    InfoCenterPane pane() {
        return pane;
    }

    ObservableList<Job> jobs() {
        return readOnlyJobs;
    }

    void start(AiSettings settings, String prompt, AiActions.Generator generator) {
        start(settings, prompt, List.of(), generator);
    }

    void start(AiSettings settings, String prompt, List<AiAttachment> attachments, AiActions.Generator generator) {
        if (disposed) return;
        Job job = new Job(settings, prompt, attachments.size());
        jobs.add(job);
        showNotification(job);
        activity.accept("Generation started: " + job.context);
        try {
            job.future = generator.start(settings, prompt, List.copyOf(attachments), job.usage);
            job.future.whenComplete((file, failure) ->
                    Platform.runLater(() -> complete(job, file, failure)));
        } catch (RuntimeException ex) {
            complete(job, null, ex);
        }
    }

    private void showNotification(Job job) {
        NotificationGroup<Job, Notification<Job>> group = new NotificationGroup<>("AI Generation");
        group.setShowHeader(false);
        group.setViewFactory(this::view);
        Notification<Job> notification = new Notification<>(job.title, job.summary);
        notification.setUserObject(job);
        if (job.state == State.READY) notification.setType(Notification.Type.SUCCESS);
        if (job.state == State.FAILED) notification.setType(Notification.Type.ERROR);
        job.notification = notification;
        group.getNotifications().add(notification);
        group.getNotifications().addListener((javafx.collections.ListChangeListener<Notification<Job>>) change -> {
            if (group.getNotifications().isEmpty()) {
                if (job.notification == notification) job.notification = null;
                pane.getInfoCenterView().getGroups().remove(group);
            }
        });
        pane.getInfoCenterView().getGroups().add(group);
        pane.setShowInfoCenter(true);
    }

    private NotificationView<Job, Notification<Job>> view(Notification<Job> notification) {
        Job job = notification.getUserObject();
        NotificationView<Job, Notification<Job>> view = new NotificationView<>(notification);
        Button cancel = new Button("Cancel");
        cancel.setId("ai-cancel-generation");
        cancel.visibleProperty().bind(notification.titleProperty().isEqualTo("Generating animation"));
        cancel.managedProperty().bind(cancel.visibleProperty());
        cancel.setOnAction(event -> cancel(job));
        Button open = new Button("Open Animation");
        open.setId("ai-open-generated");
        open.getStyleClass().add("accent");
        open.visibleProperty().bind(notification.titleProperty().isEqualTo("Animation ready"));
        open.managedProperty().bind(open.visibleProperty());
        open.setOnAction(event -> open(job));
        VBox content = new VBox(8, cancel, open);
        content.getStyleClass().add("ai-generation-notification");
        view.setContent(content);
        view.setShowContent(true);
        return view;
    }

    private void complete(Job job, AnimationFile file, Throwable failure) {
        if (disposed || job.state != State.RUNNING || (job.future != null && job.future.isCancelled())) return;
        String statistics = statistics(job);
        if (failure != null || file == null) {
            String message = failure == null ? "Generation failed." : AiErrors.message(failure);
            message = message.replace(job.prompt, "[redacted]");
            job.state = State.FAILED;
            job.title = "Generation failed";
            job.summary = message + "\n" + statistics;
            activity.accept("Generation failed (" + job.context + "): " + message);
        } else {
            job.file = file;
            job.state = State.READY;
            job.title = "Animation ready";
            job.summary = "Request: " + job.description + "\n" + statistics;
            activity.accept("Generation completed: " + job.context);
            AiPreviewFrames.thumbnail(file).whenComplete((image, error) -> Platform.runLater(() -> {
                if (!disposed && job.state == State.READY && error == null && image != null) {
                    job.preview = image;
                    changed(job);
                }
            }));
        }
        changed(job);
        if (job.notification == null) {
            showNotification(job);
            return;
        }
        job.notification.setTitle(job.title);
        job.notification.setType(job.state == State.READY ? Notification.Type.SUCCESS : Notification.Type.ERROR);
        job.notification.setSummary(job.summary);
        pane.setShowInfoCenter(true);
    }

    private static String statistics(Job job) {
        String elapsed = String.format("%.1f s", (System.nanoTime() - job.started) / 1_000_000_000.0);
        AiUsage.Snapshot usage = job.usage.snapshot();
        return job.providerDetails + "\nTime: " + elapsed + "\nInput tokens: "
                + formatTokens(usage.inputTokens(), usage.inputComplete(), usage.requests())
                + "  ·  Output tokens: " + formatTokens(usage.outputTokens(), usage.outputComplete(), usage.requests());
    }

    void cancel(Job job) {
        if (disposed || job.state != State.RUNNING) return;
        job.state = State.CANCELLED;
        job.title = "Generation cancelled";
        if (job.future != null) job.future.cancel(true);
        job.summary = "Request: " + job.description + "\n" + statistics(job);
        activity.accept("Generation cancelled: " + job.context);
        if (job.notification != null) job.notification.remove();
        changed(job);
    }

    void open(Job job) {
        if (disposed || job.file == null || job.opened) return;
        job.opened = true;
        workspace.add(EditorDocument.imported(job.file));
        if (job.notification != null) job.notification.remove();
        changed(job);
    }

    private void changed(Job job) {
        int index = jobs.indexOf(job);
        if (index >= 0) jobs.set(index, job);
    }

    private static String formatTokens(long value, boolean complete, int requests) {
        if (requests == 0) return "unavailable";
        if (complete) return Long.toString(value);
        return value == 0 ? "unavailable" : value + " (partial)";
    }

    void dispose() {
        disposed = true;
        for (Job job : List.copyOf(jobs)) {
            if (job.future != null) job.future.cancel(true);
        }
        jobs.clear();
        pane.getInfoCenterView().getGroups().clear();
    }

    enum State { RUNNING, READY, FAILED, CANCELLED }

    static final class Job {
        final String context;
        final String providerDetails;
        final String description;
        private final String prompt;
        private final long started = System.nanoTime();
        private final AiUsage usage = new AiUsage();
        private Notification<Job> notification;
        private CompletableFuture<AnimationFile> future;
        private AnimationFile file;
        State state = State.RUNNING;
        String title = "Generating animation";
        String summary;
        boolean opened;
        Image preview;

        private Job(AiSettings settings, String prompt, int attachments) {
            this.context = settings.provider() + " / " + settings.model();
            this.providerDetails = "Provider: " + (settings.provider() == AiProviderClient.Provider.OPENAI
                    ? "OpenAI" : "Anthropic") + "\nModel: " + settings.model()
                    + (attachments > 0 ? "\nAttachments: " + attachments : "");
            this.prompt = prompt;
            String text = prompt.replaceAll("\\s+", " ");
            this.description = text.length() <= 100 ? text : text.substring(0, 100) + "…";
            this.summary = "Request: " + description + "\n" + providerDetails
                    + "\nTime: in progress\nInput tokens: pending  ·  Output tokens: pending";
        }
    }
}
