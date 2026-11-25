package com.dlsc.animatorfx.editor.ui;

import com.dlsc.animatorfx.editor.ai.AiAnimation;
import com.dlsc.animatorfx.editor.ai.AiCredentialStore;
import com.dlsc.animatorfx.editor.ai.AiEditService;
import com.dlsc.animatorfx.editor.ai.AiGenerationService;
import com.dlsc.animatorfx.editor.ai.AiModelCatalog;
import com.dlsc.animatorfx.editor.ai.AiProviderClient;
import com.dlsc.animatorfx.editor.ai.AiSettings;
import com.dlsc.animatorfx.editor.audio.SoundPreview;
import com.dlsc.animatorfx.editor.audio.AudioImport;
import com.dlsc.animatorfx.editor.video.VideoImport;
import com.dlsc.animatorfx.editor.canvas.CanvasView;
import com.dlsc.animatorfx.editor.canvas.tools.VideoTool;
import com.dlsc.animatorfx.editor.doc.EditorDocument;
import com.dlsc.animatorfx.editor.inspector.sections.MotionPathSection;
import com.dlsc.animatorfx.editor.session.RecoveryStore;
import com.dlsc.animatorfx.editor.session.AppDirs;
import com.dlsc.animatorfx.editor.session.SessionState.Layout;
import com.dlsc.animatorfx.editor.session.SessionStore;
import com.dlsc.animatorfx.editor.timeline.TimelineView;
import javafx.application.ColorScheme;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextInputControl;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HeaderBar;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.WindowEvent;

import java.io.File;
import java.io.UncheckedIOException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The main window in the motion-tool layout: window header with document tabs, playback toolbar, tool rail
 * at the left, canvas in the centre, tabbed side panel at the right, timeline at the bottom and
 * the status bar below it. Above all of it lies the layer in which the dialogs show, inside
 * the window.
 *
 * <p>Canvas, side panel and timeline each sit in their own slot. Their current content
 * ({@link CanvasView}, {@link SidePanel}, {@link TimelineView}) follows the active document
 * through a {@code documentProperty()}; {@link #setCanvasContent}, {@link #setSidePanelContent}
 * and {@link #setTimelineContent} swap in another node, which then follows
 * {@code getWorkspace().activeDocumentProperty()} itself.
 */
public final class MainWindow {

    private static final String APP_NAME = "AnimatorFX";

    private final Stage stage;
    private final Theme theme = new Theme();
    private final Workspace workspace = new Workspace();
    private final NoticeBar noticeBar = new NoticeBar();
    private final DialogLayer dialogLayer = new DialogLayer();
    private final Dialogs dialogs;
    private final FileActions fileActions;
    private final ImportActions importActions;
    private final ExportActions exportActions;
    private final AiActions aiActions;
    private final AiGenerationHistory aiGenerationHistory;
    private final AiPreferencesDialog aiPreferences;
    private final ToolBarView toolBar;
    private final ToolRail toolRail = new ToolRail();
    private final StatusBar statusBar = new StatusBar();
    private final CanvasView canvasView = new CanvasView();
    private final SidePanel sidePanel = new SidePanel();
    private final AiConsole aiConsole = new AiConsole();
    private final AiChat aiChat;
    private final TimelineView timelineView = new TimelineView();
    private final StackPane canvasSlot = new StackPane(canvasView);
    private final StackPane sideSlot = new StackPane(sidePanel);
    private final StackPane timelineSlot = new StackPane(timelineView);
    private final SplitPane upperSplit = new SplitPane(canvasSlot, sideSlot);
    private final SplitPane mainSplit = new SplitPane();
    private final BooleanProperty sidePanelVisible = new SimpleBooleanProperty(this, "sidePanelVisible", true);
    private final BooleanProperty timelineVisible = new SimpleBooleanProperty(this, "timelineVisible", true);
    private final MenuBar menuBar;
    private final Scene scene;
    private final SessionController session;
    private final Path aiSettings;
    private final Function<String, String> readAiKey;
    private long aiConsoleRevision;
    private boolean closed;

    private double sideDivider = Layout.DEFAULT.sideDivider();
    private double timelineDivider = Layout.DEFAULT.timelineDivider();
    /** Until the window is up, the split panes do not hold the divider positions to keep. */
    private boolean dividersPlaced;
    private Runnable onLayoutChanged = () -> {
    };
    private final ChangeListener<Object> layoutChanged = (obs, oldValue, newValue) -> onLayoutChanged.run();

    /**
     * Builds the window with the dialogs of the application and restores the session from the stores.
     */
    public MainWindow(Stage stage, SessionStore sessionStore, RecoveryStore recoveryStore) {
        this(stage, sessionStore, recoveryStore, null);
    }

    /**
     * @param dialogs the dialogs of the file actions; {@code null} for the dialogs of the application
     */
    public MainWindow(Stage stage, SessionStore sessionStore, RecoveryStore recoveryStore, Dialogs dialogs) {
        this(stage, sessionStore, recoveryStore, dialogs,
                provider -> new AiCredentialStore().get(provider), new AiModelCatalog()::listModels);
    }

    MainWindow(Stage stage, SessionStore sessionStore, RecoveryStore recoveryStore, Dialogs dialogs,
               Function<String, String> readAiKey,
               BiFunction<AiProviderClient.Provider, String, CompletableFuture<List<String>>> listModels) {
        this(stage, sessionStore, recoveryStore, dialogs, readAiKey, listModels,
                AppDirs.configDir().resolve("ai.properties"),
                (provider, key) -> new AiCredentialStore().put(provider, key),
                provider -> new AiCredentialStore().delete(provider));
    }

    MainWindow(Stage stage, SessionStore sessionStore, RecoveryStore recoveryStore, Dialogs dialogs,
               Function<String, String> readAiKey,
               BiFunction<AiProviderClient.Provider, String, CompletableFuture<List<String>>> listModels,
               Path aiSettings, BiConsumer<String, String> putAiKey, Consumer<String> deleteAiKey) {
        this.stage = stage;
        stage.initStyle(StageStyle.EXTENDED);
        Image icon = new Image(MainWindow.class.getResource(
                "/com/dlsc/animatorfx/editor/app-icon.png").toExternalForm());
        stage.getIcons().add(icon);
        this.dialogs = dialogs != null ? dialogs : new SystemDialogs(dialogLayer);
        fileActions = new FileActions(workspace, this.dialogs, noticeBar, () -> stage);
        importActions = new ImportActions(workspace, noticeBar, this.dialogs, () -> stage);
        exportActions = new ExportActions(workspace, noticeBar, this.dialogs, () -> stage);
        this.aiSettings = aiSettings;
        this.readAiKey = readAiKey;
        AiProviderClient aiClient = new AiProviderClient();
        Supplier<AiSettings> loadAiSettings = () -> {
            try {
                return AiSettings.load(aiSettings);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        };
        AiEditService editor = new AiEditService(readAiKey, aiClient::edit, aiClient::review,
                message -> Platform.runLater(() -> aiConsole.log(message)));
        aiChat = new AiChat(workspace, loadAiSettings, (settings, animation, selection, time, history, prompt) ->
                editor.edit(settings, workspace.getActiveDocument().getFileContent(), selection, time, history, prompt),
                aiConsole::log);
        aiConsole.installChat(aiChat);
        AiNotifications notifications = new AiNotifications(workspace, aiConsole::log);
        aiGenerationHistory = new AiGenerationHistory(notifications, theme, this::showAiGeneration);
        aiActions = new AiActions(dialogLayer, notifications, loadAiSettings, (settings, prompt, attachments, usage) -> {
            AiGenerationService generator = new AiGenerationService(aiSettings, readAiKey,
                    (provider, model, key, input) -> aiClient.generate(provider, model, key, input,
                            AiAnimation.schema(), usage, attachments),
                    (provider, model, key, context, frames, schema, instructions) ->
                            aiClient.review(provider, model, key, context, frames, schema, instructions, usage),
                    message -> Platform.runLater(() -> aiConsole.log(message)));
            return generator.generate(settings, prompt, attachments);
        });
        aiPreferences = new AiPreferencesDialog(dialogLayer, aiSettings,
                putAiKey, deleteAiKey, readAiKey, listModels, () -> {
                    aiChat.refresh();
                    refreshAiConsole();
                });
        HeaderBarView header = new HeaderBarView(
                new DocumentTabs(workspace, fileActions::close, fileActions::newDocument), theme, icon, canvasView);
        header.addGenerationButton(notifications.runningProperty(), aiGenerationHistory::show);
        HeaderBar.systemColorSchemeProperty(stage).bind(Bindings.when(theme.darkProperty())
                .then(ColorScheme.DARK).otherwise(ColorScheme.LIGHT));
        HeaderBar.systemButtonHeightProperty(stage).bind(header.heightProperty());
        toolBar = new ToolBarView();

        canvasSlot.setMinSize(0, 0);
        sideSlot.setMinWidth(220);
        timelineSlot.setMinHeight(80);
        upperSplit.getStyleClass().add("upper-split");
        BorderPane upper = new BorderPane(upperSplit);
        upper.setLeft(toolRail);
        upper.setMinHeight(120);
        mainSplit.setOrientation(Orientation.VERTICAL);
        mainSplit.getStyleClass().add("main-split");
        mainSplit.getItems().addAll(upper, timelineSlot);
        upperSplit.setDividerPositions(sideDivider);
        mainSplit.setDividerPositions(timelineDivider);

        menuBar = new MenuBarFactory(this).build();
        if (menuBar.isUseSystemMenuBar() && MenuBarFactory.MAC) {
            // the menus are in the system menu bar; the node must stay in the scene but takes no space
            menuBar.setMinHeight(0);
            menuBar.setPrefHeight(0);
            menuBar.setMaxHeight(0);
        }
        BorderPane content = new BorderPane(mainSplit);
        content.getStyleClass().add("main-window");
        content.setTop(new VBox(header, menuBar, toolBar, noticeBar));
        content.setBottom(statusBar);
        // the dialog layer covers everything, an in-window menu bar included
        notifications.pane().setContent(content);
        StackPane root = new StackPane(notifications.pane(), dialogLayer.getPane());
        // no scene size: the stage is sized by the session before it shows
        scene = new Scene(root);
        theme.install(scene);

        for (var view : List.of(toolBar.documentProperty(), statusBar.documentProperty(),
                canvasView.documentProperty(), sidePanel.documentProperty(), timelineView.documentProperty())) {
            view.bind(workspace.activeDocumentProperty());
        }
        workspace.activeDocumentProperty().addListener((obs, oldDoc, newDoc) -> bindTitle(newDoc));
        bindTitle(null);
        installCanvas();
        installPanelToggles();
        installToolShortcuts();
        installFileDrop();

        stage.setScene(scene);
        stage.setMinWidth(900);
        stage.setMinHeight(560);
        stage.setOnCloseRequest(e -> {
            if (!tryShutdown()) {
                e.consume();
            }
        });
        // The first layout of a window can run before the window has its real size, which moves the
        // dividers. So they are positioned once the window is up; from then on side panel and
        // timeline keep their size when the window is resized.
        stage.addEventHandler(WindowEvent.WINDOW_SHOWN, e -> Platform.runLater(() -> {
            if (isSidePanelVisible()) {
                upperSplit.setDividerPositions(sideDivider);
            }
            if (isTimelineVisible()) {
                mainSplit.setDividerPositions(timelineDivider);
            }
            SplitPane.setResizableWithParent(sideSlot, false);
            SplitPane.setResizableWithParent(timelineSlot, false);
            dividersPlaced = true;
        }));

        session = new SessionController(this, sessionStore, recoveryStore);
        session.restore();
        session.start();
        refreshAiConsole();
    }

    public void show() {
        session.showWindow();
    }

    /**
     * Stops playback and previews, then persists the session and dirty recovery copies. It does
     * not ask to save or hide the window. A close request is cancelled if recovery cannot safely
     * drain, leaving the window open.
     */
    public void shutdown() {
        tryShutdown();
    }

    private boolean tryShutdown() {
        runShutdownSteps(List.of(canvasView::pausePlayback, SoundPreview.shared()::stop));
        if (!session.shutdown()) {
            return false;
        }
        closed = true;
        runShutdownSteps(List.of(aiGenerationHistory::dispose, aiActions::dispose, aiChat::dispose,
                canvasView::dispose, theme::dispose));
        return true;
    }

    private static void runShutdownSteps(List<Runnable> steps) {
        for (Runnable step : steps) {
            try {
                step.run();
            } catch (RuntimeException | LinkageError | StackOverflowError e) {
                System.getLogger(MainWindow.class.getName()).log(System.Logger.Level.WARNING,
                        "A step of closing the window failed", e);
            }
        }
    }

    public Stage getStage() {
        return stage;
    }

    public Scene getScene() {
        return scene;
    }

    public Theme getTheme() {
        return theme;
    }

    public Workspace getWorkspace() {
        return workspace;
    }

    public FileActions getFileActions() {
        return fileActions;
    }

    public ImportActions getImportActions() {
        return importActions;
    }

    public ExportActions getExportActions() {
        return exportActions;
    }

    void showAiGeneration() {
        aiActions.generate();
    }

    void showAiPreferences() {
        aiPreferences.show();
    }

    private void refreshAiConsole() {
        if (closed) return;
        long revision = ++aiConsoleRevision;
        sidePanel.setAiConsoleAvailable(aiConsole, false);
        CompletableFuture.supplyAsync(() -> {
            try {
                AiSettings settings = AiSettings.load(aiSettings);
                String key = readAiKey.apply(settings.provider().name());
                return key != null && !key.isBlank() && key.length() <= 1000;
            } catch (Exception | LinkageError ex) {
                return false;
            }
        }).thenAccept(available -> Platform.runLater(() -> {
            if (!closed && revision == aiConsoleRevision) sidePanel.setAiConsoleAvailable(aiConsole, available);
        }));
    }

    public Dialogs getDialogs() {
        return dialogs;
    }

    /**
     * Whether a dialog is showing inside the window. Meanwhile the keyboard belongs to the
     * dialog, and neither shortcuts nor menu items act on the document.
     */
    public boolean isDialogShowing() {
        return dialogLayer.isShowing();
    }

    public SessionController getSession() {
        return session;
    }

    public MenuBar getMenuBar() {
        return menuBar;
    }

    public ToolBarView getToolBar() {
        return toolBar;
    }

    public ToolRail getToolRail() {
        return toolRail;
    }

    public StatusBar getStatusBar() {
        return statusBar;
    }

    public NoticeBar getNoticeBar() {
        return noticeBar;
    }

    /**
     * The interactive canvas with its tools, overlay and playback.
     */
    public CanvasView getCanvasView() {
        return canvasView;
    }

    public SidePanel getSidePanel() {
        return sidePanel;
    }

    /**
     * The timeline with the layer tree, the ruler, the markers and the keyframe tracks.
     */
    public TimelineView getTimelineView() {
        return timelineView;
    }

    /**
     * Replaces what the centre region shows.
     */
    public void setCanvasContent(Node content) {
        canvasSlot.getChildren().setAll(content);
    }

    /**
     * Replaces what the right region shows.
     */
    public void setSidePanelContent(Node content) {
        sideSlot.getChildren().setAll(content);
    }

    /**
     * Replaces what the bottom region shows.
     */
    public void setTimelineContent(Node content) {
        timelineSlot.getChildren().setAll(content);
    }

    /**
     * The menu or menu item with that id, at any depth.
     *
     * @throws IllegalArgumentException if there is none
     */
    public MenuItem menuItem(String id) {
        MenuItem found = find(menuBar.getMenus(), id);
        if (found == null) {
            throw new IllegalArgumentException("No menu item with the id " + id);
        }
        return found;
    }

    /**
     * Whether the side panel is showing; hiding it gives its space to the canvas.
     *
     * @return the side-panel-visible property
     */
    public final BooleanProperty sidePanelVisibleProperty() {
        return sidePanelVisible;
    }

    public final boolean isSidePanelVisible() {
        return sidePanelVisible.get();
    }

    public final void setSidePanelVisible(boolean value) {
        sidePanelVisible.set(value);
    }

    /**
     * Whether the timeline is showing; hiding it gives its space to the canvas.
     *
     * @return the timeline-visible property
     */
    public final BooleanProperty timelineVisibleProperty() {
        return timelineVisible;
    }

    public final boolean isTimelineVisible() {
        return timelineVisible.get();
    }

    public final void setTimelineVisible(boolean value) {
        timelineVisible.set(value);
    }

    /**
     * Divider positions, collapsed panels and the active side-panel tab, as the session stores them.
     */
    public Layout getLayoutState() {
        if (dividersPlaced && isSidePanelVisible()) {
            sideDivider = upperSplit.getDividerPositions()[0];
        }
        if (dividersPlaced && isTimelineVisible()) {
            timelineDivider = mainSplit.getDividerPositions()[0];
        }
        return new Layout(sideDivider, timelineDivider, !isSidePanelVisible(), !isTimelineVisible(),
                sidePanel.getActiveTab());
    }

    public void applyLayout(Layout layout) {
        // hiding a panel remembers its current divider, so the new positions are set afterwards
        setSidePanelVisible(!layout.sidePanelCollapsed());
        setTimelineVisible(!layout.timelineCollapsed());
        sideDivider = clamp(layout.sideDivider(), Layout.DEFAULT.sideDivider());
        timelineDivider = clamp(layout.timelineDivider(), Layout.DEFAULT.timelineDivider());
        if (isSidePanelVisible()) {
            upperSplit.setDividerPositions(sideDivider);
        }
        if (isTimelineVisible()) {
            mainSplit.setDividerPositions(timelineDivider);
        }
        sidePanel.setActiveTab(layout.sideTab());
    }

    /** Called whenever a divider moves, a panel collapses or expands, or the side-panel tab changes. */
    void setOnLayoutChanged(Runnable handler) {
        onLayoutChanged = handler;
    }

    private static double clamp(double position, double fallback) {
        return position > 0.05 && position < 0.95 ? position : fallback;
    }

    /** Connects the canvas to the tool rail, the transport of the toolbar and the notice bar. */
    private void installCanvas() {
        canvasView.setTool(ToolKind.VIDEO, new VideoTool(canvasView, importActions::chooseAndImportVideo));
        canvasView.activeToolProperty().bindBidirectional(toolRail.activeToolProperty());
        canvasView.loopProperty().bind(toolBar.loopProperty());
        toolBar.playingProperty().bind(canvasView.playingProperty());
        toolBar.setOnPlayPause(canvasView::togglePlayback);
        canvasView.mutedProperty().bindBidirectional(toolBar.mutedProperty());
        // a preview and playback do not sound at the same time
        canvasView.playingProperty().addListener((obs, was, playing) -> {
            if (playing) {
                SoundPreview.shared().stop();
            }
        });
        timelineView.setScrubber(time -> {
            if (!isDialogShowing()) {
                canvasView.scrub(time);
            }
        });
        canvasView.setNotifier(noticeBar::show);
        canvasView.setKeyboardTaken(this::isDialogShowing);
        timelineView.setNotifier(noticeBar::show);
        timelineView.setOnClose(() -> setTimelineVisible(false));
        sidePanel.setOnClose(() -> setSidePanelVisible(false));
        timelineView.setSoundImporter(() -> importActions.chooseAndImportCue(timelineView::selectCue));
        sidePanel.getInspector().section(MotionPathSection.class)
                .ifPresent(section -> section.setActions(canvasView::drawMotionPath, canvasView::editMotionPath));
    }

    private void installPanelToggles() {
        sidePanelVisible.addListener((obs, was, visible) -> {
            if (visible) {
                upperSplit.getItems().add(sideSlot);
                upperSplit.setDividerPositions(sideDivider);
            } else {
                if (dividersPlaced) {
                    sideDivider = upperSplit.getDividerPositions()[0];
                }
                upperSplit.getItems().remove(sideSlot);
            }
        });
        timelineVisible.addListener((obs, was, visible) -> {
            if (visible) {
                mainSplit.getItems().add(timelineSlot);
                mainSplit.setDividerPositions(timelineDivider);
            } else {
                if (dividersPlaced) {
                    timelineDivider = mainSplit.getDividerPositions()[0];
                }
                mainSplit.getItems().remove(timelineSlot);
            }
        });
        for (SplitPane split : List.of(upperSplit, mainSplit)) {
            split.getDividers().forEach(d -> d.positionProperty().addListener(layoutChanged));
            split.getDividers().addListener((ListChangeListener<SplitPane.Divider>) change -> {
                while (change.next()) {
                    change.getAddedSubList().forEach(d -> d.positionProperty().addListener(layoutChanged));
                }
                onLayoutChanged.run();
            });
        }
        sidePanel.getTabPane().getSelectionModel().selectedItemProperty().addListener(layoutChanged);
    }

    /**
     * A tool key pressed without modifiers selects the tool, and M switches the sound off and on,
     * unless a dialog is showing, the user is typing text or is in the middle of drawing or
     * editing a path.
     */
    private void installToolShortcuts() {
        scene.addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            if (isDialogShowing() || event.isShortcutDown() || event.isControlDown() || event.isAltDown() || event.isMetaDown()
                    || scene.getFocusOwner() instanceof TextInputControl || canvasView.blocksToolShortcuts()) {
                return;
            }
            if (event.getCode() == KeyCode.M && !event.isShiftDown()) {
                toolBar.setMuted(!toolBar.isMuted());
                event.consume();
                return;
            }
            ToolKind.forShortcut(event.getCode()).ifPresent(kind -> {
                toolRail.setActiveTool(kind);
                event.consume();
            });
        });
    }

    private void installFileDrop() {
        scene.setOnDragOver(event -> {
            if (!isDialogShowing() && event.getDragboard().hasFiles() && event.getDragboard().getFiles().stream().anyMatch(MainWindow::isDroppable)) {
                event.acceptTransferModes(TransferMode.COPY);
            }
            event.consume();
        });
        scene.setOnDragDropped(event -> {
            event.setDropCompleted(!isDialogShowing() && event.getDragboard().hasFiles() && openDropped(event.getDragboard().getFiles()));
            event.consume();
        });
    }

    /**
     * Opens the {@code .afx} files among the dropped files and imports the SVG ({@code .svg}),
     * Lottie ({@code .json}) and FXML ({@code .fxml}) files, each as a document of its own.
     * Sound files become audio layers of the active document. Images are taken by the canvas.
     *
     * @return whether any file was taken
     */
    boolean openDropped(List<File> files) {
        boolean opened = false;
        for (File file : files) {
            if (!isDroppable(file)) {
                continue;
            }
            if (VideoImport.isVideo(file.toPath())) {
                EditorDocument document = workspace.getActiveDocument();
                if (document != null) {
                    importActions.importVideo(document, file.toPath());
                    opened = true;
                }
                continue;
            }
            if (AudioImport.isAudio(file.toPath())) {
                EditorDocument document = workspace.getActiveDocument();
                if (document != null) {
                    importActions.importAudio(document, file.toPath());
                    opened = true;
                }
                continue;
            }
            Optional<ImportActions.Kind> kind = ImportActions.Kind.of(file.toPath());
            opened |= kind.isPresent() ? importActions.importFile(file.toPath(), kind.get()).isPresent()
                    : fileActions.open(file.toPath()).isPresent();
        }
        return opened;
    }

    static boolean isDroppable(File file) {
        return file.isFile() && (file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".afx")
                || ImportActions.Kind.of(file.toPath()).isPresent() || AudioImport.isAudio(file.toPath())
                || VideoImport.isVideo(file.toPath()));
    }

    private void bindTitle(EditorDocument document) {
        stage.titleProperty().unbind();
        if (document == null) {
            stage.setTitle(APP_NAME);
        } else {
            stage.titleProperty().bind(Bindings.concat(APP_NAME, " — ", document.titleProperty()));
        }
    }

    private static MenuItem find(List<? extends MenuItem> items, String id) {
        for (MenuItem item : items) {
            if (id.equals(item.getId())) {
                return item;
            }
            if (item instanceof Menu menu) {
                MenuItem found = find(menu.getItems(), id);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
