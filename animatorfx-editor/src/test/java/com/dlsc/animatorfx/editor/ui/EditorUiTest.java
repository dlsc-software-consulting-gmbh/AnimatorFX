package com.dlsc.animatorfx.editor.ui;

import com.dlsc.animatorfx.editor.audio.EditorAudio;
import com.dlsc.animatorfx.editor.canvas.CanvasView;
import com.dlsc.animatorfx.editor.doc.Commands;
import com.dlsc.animatorfx.editor.doc.EditorDocument;
import com.dlsc.animatorfx.editor.session.RecoveryStore;
import com.dlsc.animatorfx.editor.session.SessionStore;
import com.dlsc.animatorfx.editor.timeline.TimelineView;
import com.dlsc.animatorfx.model.Animatable;
import com.dlsc.animatorfx.model.Geometry;
import com.dlsc.animatorfx.model.GroupLayer;
import com.dlsc.animatorfx.model.Keyframe;
import com.dlsc.animatorfx.model.Layer;
import com.dlsc.animatorfx.model.LayerBase;
import com.dlsc.animatorfx.model.LayerProperties;
import com.dlsc.animatorfx.model.Layers;
import com.dlsc.animatorfx.model.Paint;
import com.dlsc.animatorfx.model.Rgba;
import com.dlsc.animatorfx.model.ShapeLayer;
import com.dlsc.animatorfx.model.Transform;
import com.dlsc.animatorfx.model.Vec2;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Callable;
import javafx.event.Event;
import javafx.event.EventType;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;

/**
 * A main window under the headless toolkit with one untitled document (800 x 600, 3 s, 60 fps),
 * and helpers that drive any node of it with mouse and key events at node coordinates.
 */
public abstract class EditorUiTest {

    static {
        // The build runs the editor's tests with this property; a run from an IDE does not go
        // through the build and must be just as silent.
        System.setProperty(EditorAudio.PROPERTY, "off");
    }

    private static final class NoDialogs implements Dialogs {

        private final java.util.function.DoubleFunction<CompletableFuture<AudioPlacement>> audioInsertion;

        NoDialogs(java.util.function.DoubleFunction<CompletableFuture<AudioPlacement>> audioInsertion) {
            this.audioInsertion = audioInsertion;
        }

        @Override
        public CompletableFuture<AudioPlacement> chooseAudioInsertion(double cursorTime) {
            return audioInsertion.apply(cursorTime);
        }

        @Override
        public Optional<Path> chooseOpenFile(Window owner) {
            return Optional.empty();
        }

        @Override
        public Optional<Path> chooseSaveFile(Window owner, String suggestedName) {
            return Optional.empty();
        }

        @Override
        public CompletableFuture<CloseChoice> confirmClose(String documentTitle) {
            return CompletableFuture.completedFuture(CloseChoice.DISCARD);
        }

        @Override
        public CompletableFuture<Boolean> confirmRevert(String documentTitle) {
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public void showAbout() {
        }

        @Override
        public void showWarnings(String title, List<String> lines) {
        }
    }

    /** Modifier keys of an event. */
    public record Keys(boolean shift, boolean shortcut, boolean alt) {

        public static final Keys NONE = new Keys(false, false, false);
        public static final Keys SHIFT = new Keys(true, false, false);
        public static final Keys SHORTCUT = new Keys(false, true, false);
        public static final Keys ALT = new Keys(false, false, true);
    }

    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase().contains("mac");

    @TempDir
    public Path dir;

    protected MainWindow window;
    protected CanvasView canvas;
    protected TimelineView timeline;
    protected EditorDocument doc;

    protected CompletableFuture<Dialogs.AudioPlacement> chooseAudioInsertion(double time) {
        return CompletableFuture.completedFuture(Dialogs.AudioPlacement.CURSOR);
    }

    @BeforeAll
    public static void startToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @BeforeEach
    public void openWindow() {
        window = fx(() -> {
            MainWindow w = new MainWindow(new Stage(), new SessionStore(dir.resolve("config")),
                    new RecoveryStore(dir.resolve("config").resolve("recovery")), new NoDialogs(this::chooseAudioInsertion),
                    provider -> null, (provider, key) -> CompletableFuture.completedFuture(List.of()),
                    dir.resolve("config").resolve("ai.properties"), (provider, key) -> {}, provider -> {});
            // the headless screen is 1280 x 800
            w.getStage().setWidth(1240);
            w.getStage().setHeight(780);
            w.show();
            return w;
        });
        WaitForAsyncUtils.waitForFxEvents();
        canvas = window.getCanvasView();
        timeline = window.getTimelineView();
        doc = window.getWorkspace().getActiveDocument();
        layout();
    }

    @AfterEach
    public void closeWindow() {
        run(() -> {
            window.shutdown();
            window.getStage().hide();
        });
    }

    public static <T> T fx(Callable<T> action) {
        try {
            return WaitForAsyncUtils.asyncFx(action).get();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    public static void run(Runnable action) {
        fx(() -> {
            action.run();
            return null;
        });
    }

    /** Runs CSS and layout, which brings the timeline and the inspector up to date. */
    protected void layout() {
        run(() -> {
            window.getScene().getRoot().applyCss();
            window.getScene().getRoot().layout();
        });
    }

    // ------------------------------------------------------------------ document helpers

    protected static ShapeLayer rect(String id, double x, double y, double width, double height) {
        LayerBase base = LayerBase.create(id, 3).withId(id).withTransform(Transform.IDENTITY
                .withAnchor(Animatable.of(new Vec2(width / 2, height / 2)))
                .withPosition(Animatable.of(new Vec2(x + width / 2, y + height / 2))));
        return new ShapeLayer(base, new Geometry.Rect(Animatable.of(new Vec2(width, height)), Animatable.of(0.0)),
                Animatable.of(new Paint.Solid(Rgba.WHITE)), null, null);
    }

    protected static GroupLayer group(String id, Layer... children) {
        return new GroupLayer(LayerBase.create(id, 3).withId(id), List.of(children));
    }

    /** Adds layers on top, bottom first, and lays out. */
    protected void add(Layer... layers) {
        run(() -> {
            for (Layer layer : layers) {
                doc.execute(Commands.addLayer(null, doc.getAnimation().layers().size(), layer));
            }
        });
        layout();
    }

    protected void execute(com.dlsc.animatorfx.editor.doc.Command command) {
        run(() -> doc.execute(command));
        layout();
    }

    protected Layer layer(String id) {
        return Layers.find(doc.getAnimation(), id).orElseThrow();
    }

    protected Animatable<?> value(String id, String key) {
        return LayerProperties.get(layer(id), key);
    }

    protected List<? extends Keyframe<?>> keyframes(String id, String key) {
        return value(id, key).keyframes();
    }

    protected List<String> layerIds() {
        return doc.getAnimation().layers().stream().map(Layer::id).toList();
    }

    protected List<String> selection() {
        return List.copyOf(doc.getSelection().layerIds());
    }

    protected void select(String... ids) {
        run(() -> doc.getSelection().layerIds().setAll(ids));
        layout();
    }

    protected void playhead(double time) {
        run(() -> doc.setPlayhead(time));
        layout();
    }

    // ------------------------------------------------------------------ input

    protected void mouse(Node node, EventType<MouseEvent> type, double x, double y, Keys keys, int clicks,
                         MouseButton button) {
        run(() -> {
            Point2D scene = node.localToScene(x, y);
            Point2D screen = node.localToScreen(x, y);
            boolean down = button == MouseButton.PRIMARY && type != MouseEvent.MOUSE_RELEASED
                    && type != MouseEvent.MOUSE_MOVED && type != MouseEvent.MOUSE_CLICKED;
            Event.fireEvent(node, new MouseEvent(type, scene.getX(), scene.getY(),
                    screen == null ? scene.getX() : screen.getX(), screen == null ? scene.getY() : screen.getY(),
                    type == MouseEvent.MOUSE_MOVED ? MouseButton.NONE : button, clicks,
                    keys.shift(), !MAC && keys.shortcut(), keys.alt(), MAC && keys.shortcut(), down, false,
                    button == MouseButton.SECONDARY && type == MouseEvent.MOUSE_PRESSED, false, false, false, null));
        });
    }

    protected void press(Node node, double x, double y, Keys keys) {
        mouse(node, MouseEvent.MOUSE_PRESSED, x, y, keys, 1, MouseButton.PRIMARY);
    }

    protected void dragTo(Node node, double x, double y, Keys keys) {
        mouse(node, MouseEvent.MOUSE_DRAGGED, x, y, keys, 1, MouseButton.PRIMARY);
        layout();
    }

    protected void release(Node node, double x, double y, Keys keys) {
        mouse(node, MouseEvent.MOUSE_RELEASED, x, y, keys, 1, MouseButton.PRIMARY);
        layout();
    }

    protected void click(Node node, double x, double y, Keys keys) {
        press(node, x, y, keys);
        mouse(node, MouseEvent.MOUSE_RELEASED, x, y, keys, 1, MouseButton.PRIMARY);
        mouse(node, MouseEvent.MOUSE_CLICKED, x, y, keys, 1, MouseButton.PRIMARY);
        layout();
    }

    protected void click(Node node, double x, double y) {
        click(node, x, y, Keys.NONE);
    }

    protected void doubleClick(Node node, double x, double y) {
        click(node, x, y);
        mouse(node, MouseEvent.MOUSE_PRESSED, x, y, Keys.NONE, 2, MouseButton.PRIMARY);
        mouse(node, MouseEvent.MOUSE_RELEASED, x, y, Keys.NONE, 2, MouseButton.PRIMARY);
        mouse(node, MouseEvent.MOUSE_CLICKED, x, y, Keys.NONE, 2, MouseButton.PRIMARY);
        layout();
    }

    /** Press, drag in three steps and release, all in the coordinates of the node. */
    protected void drag(Node node, double fromX, double fromY, double toX, double toY, Keys keys) {
        press(node, fromX, fromY, keys);
        for (int i = 1; i <= 3; i++) {
            dragTo(node, fromX + (toX - fromX) * i / 3, fromY + (toY - fromY) * i / 3, keys);
        }
        release(node, toX, toY, keys);
    }

    protected void drag(Node node, double fromX, double fromY, double toX, double toY) {
        drag(node, fromX, fromY, toX, toY, Keys.NONE);
    }

    /** A key press and release on the focus owner. */
    protected void key(KeyCode code) {
        run(() -> {
            Node target = window.getScene().getFocusOwner() != null ? window.getScene().getFocusOwner() : canvas;
            Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
            Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_RELEASED, "", "", code, false, false, false, false));
        });
        layout();
    }

    protected void key(KeyCode code, Keys keys) {
        run(() -> {
            Node target = window.getScene().getFocusOwner() != null ? window.getScene().getFocusOwner() : canvas;
            Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, keys.shift(),
                    !MAC && keys.shortcut(), keys.alt(), MAC && keys.shortcut()));
            Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_RELEASED, "", "", code, keys.shift(),
                    !MAC && keys.shortcut(), keys.alt(), MAC && keys.shortcut()));
        });
        layout();
    }

    protected Node focusOwner() {
        return fx(() -> window.getScene().getFocusOwner());
    }

    @SuppressWarnings("unchecked")
    protected <T extends Node> T lookup(String selector) {
        T node = (T) fx(() -> window.getScene().lookup(selector));
        if (node == null) {
            throw new AssertionError("No node for " + selector);
        }
        return node;
    }
}
