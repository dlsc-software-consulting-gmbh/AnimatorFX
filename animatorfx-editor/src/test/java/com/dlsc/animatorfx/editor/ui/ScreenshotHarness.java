package com.dlsc.animatorfx.editor.ui;

import com.dlsc.animatorfx.editor.canvas.CanvasView;
import com.dlsc.animatorfx.editor.canvas.tools.PathEditTool;
import com.dlsc.animatorfx.editor.doc.EditorDocument;
import com.dlsc.animatorfx.editor.inspector.InspectorSection;
import com.dlsc.animatorfx.editor.inspector.InspectorView;
import com.dlsc.animatorfx.editor.inspector.PropertyRows;
import com.dlsc.animatorfx.editor.inspector.SectionBase;
import com.dlsc.animatorfx.editor.inspector.fields.NumberField;
import com.dlsc.animatorfx.editor.inspector.fields.PaintField;
import com.dlsc.animatorfx.editor.inspector.sections.EffectsSection;
import com.dlsc.animatorfx.editor.inspector.sections.FillSection;
import com.dlsc.animatorfx.editor.inspector.sections.GeometrySection;
import com.dlsc.animatorfx.editor.inspector.sections.LayerSection;
import com.dlsc.animatorfx.editor.inspector.sections.MaskSection;
import com.dlsc.animatorfx.editor.inspector.sections.MotionPathSection;
import com.dlsc.animatorfx.editor.inspector.sections.StrokeSection;
import com.dlsc.animatorfx.editor.inspector.sections.TransformSection;
import com.dlsc.animatorfx.editor.inspector.sections.TrimSection;
import com.dlsc.animatorfx.editor.session.RecoveryStore;
import com.dlsc.animatorfx.editor.session.SessionState;
import com.dlsc.animatorfx.editor.session.SessionStore;
import com.dlsc.animatorfx.editor.timeline.LayerTreeColumn;
import com.dlsc.animatorfx.editor.timeline.TimelineView;
import com.dlsc.animatorfx.model.Vec2;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import javafx.event.ActionEvent;
import javafx.event.Event;
import javafx.event.EventType;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ComboBox;
import javafx.scene.control.MenuButton;
import javafx.scene.control.TextField;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.transform.Transform;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;

/**
 * Not a test: builds a document in a real window the way a user would and renders the main
 * window in both themes to PNG files, for looking at the result. The scenario is the one of the
 * pen and the advanced features: paths drawn with the pen tool, effects, a mask, trim and a
 * motion path set up in the inspector, path keyframes made in path editing with auto-key. It is
 * not picked up by a normal build. Run it without the headless backend flags:
 *
 * <pre>
 * mvn -pl animatorfx-editor test -Dtest=ScreenshotHarness -Dsurefire.failIfNoSpecifiedTests=false \
 *   -Dsurefire.jvm.args="--add-reads org.kordamp.ikonli.core=ALL-UNNAMED --enable-native-access=javafx.graphics
 *                        -Danimatorfx.screenshots=DIR [-Danimatorfx.screenshots.prefix=task-9]
 *                        [-Danimatorfx.screenshots.extra=true]"
 * </pre>
 *
 * which writes {@code DIR/task-9-dark.png} and {@code DIR/task-9-light.png}, and with the extra
 * flag detail pictures to {@code DIR/task-9-details}. Only the scene of the application is
 * rendered, never the screen.
 */
class ScreenshotHarness {

    private static final String DIR_PROPERTY = "animatorfx.screenshots";
    private static final double SCALE = 2;

    private String orb;
    private String masked;
    private String wave;
    private String traveller;
    private String shape;
    private MainWindow window;
    private CanvasView canvas;
    private TimelineView timeline;
    private InspectorView inspector;
    private EditorDocument doc;

    @Test
    @EnabledIfSystemProperty(named = DIR_PROPERTY, matches = ".+")
    void renderBothThemes() throws Exception {
        Path out = Path.of(System.getProperty(DIR_PROPERTY));
        String prefix = System.getProperty(DIR_PROPERTY + ".prefix", "task-9");
        Files.createDirectories(out);
        Path config = Files.createTempDirectory(out, "config-");

        FxToolkit.registerPrimaryStage();
        window = fx(() -> {
            MainWindow w = new MainWindow(new Stage(), new SessionStore(config),
                    new RecoveryStore(config.resolve("recovery")));
            w.getStage().setWidth(1360);
            w.getStage().setHeight(960);
            w.show();
            return w;
        });
        canvas = window.getCanvasView();
        timeline = window.getTimelineView();
        inspector = window.getSidePanel().getInspector();
        doc = window.getWorkspace().getActiveDocument();
        try {
            settle();
            fx(() -> {
                window.applyLayout(new SessionState.Layout(0.775, 0.615, false, false,
                        SidePanel.INSPECTOR));
                return null;
            });
            settle();
            buildScene();
            animate();
            for (Theme.Mode mode : new Theme.Mode[]{Theme.Mode.DARK, Theme.Mode.LIGHT}) {
                fx(() -> {
                    window.getTheme().setMode(mode);
                    return null;
                });
                Thread.sleep(1200);
                settle();
                WritableImage image = fx(() -> {
                    SnapshotParameters parameters = new SnapshotParameters();
                    parameters.setTransform(Transform.scale(SCALE, SCALE));
                    return window.getScene().getRoot().snapshot(parameters, null);
                });
                Path file = out.resolve(prefix + "-" + mode.name().toLowerCase() + ".png");
                Files.write(file, PngEncoder.encode(image));
                System.out.println("Wrote " + file + " zoom " + canvas.getZoom());
            }
            if (Boolean.getBoolean(DIR_PROPERTY + ".extra")) {
                fx(() -> {
                    window.getTheme().setMode(Theme.Mode.DARK);
                    return null;
                });
                Thread.sleep(1200);
                extras(out.resolve(prefix + "-details"));
            }
        } finally {
            fx(() -> {
                window.shutdown();
                window.getStage().hide();
                return null;
            });
            try (var files = Files.walk(config)) {
                files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    /**
     * The dialogs of the editor above the same document: the question of closing in both themes,
     * About, and the warnings of an import in both themes. Add
     * {@code -Danimatorfx.screenshots.dialogs=true}; the files are {@code task-12-close-dark.png},
     * {@code -close-light}, {@code -about-dark}, {@code -warnings-dark} and {@code -warnings-light}.
     */
    @Test
    @EnabledIfSystemProperty(named = DIR_PROPERTY + ".dialogs", matches = "true")
    void renderDialogs() throws Exception {
        Path out = Path.of(System.getProperty(DIR_PROPERTY));
        String prefix = System.getProperty(DIR_PROPERTY + ".prefix", "task-12");
        Files.createDirectories(out);
        Path config = Files.createTempDirectory(out, "config-");

        FxToolkit.registerPrimaryStage();
        window = fx(() -> {
            MainWindow w = new MainWindow(new Stage(), new SessionStore(config),
                    new RecoveryStore(config.resolve("recovery")));
            w.getStage().setWidth(1360);
            w.getStage().setHeight(960);
            w.show();
            return w;
        });
        canvas = window.getCanvasView();
        timeline = window.getTimelineView();
        inspector = window.getSidePanel().getInspector();
        doc = window.getWorkspace().getActiveDocument();
        try {
            settle();
            fx(() -> {
                window.applyLayout(new SessionState.Layout(0.775, 0.615, false, false, SidePanel.INSPECTOR));
                return null;
            });
            settle();
            buildScene();
            animate();

            javafx.application.Platform.runLater(() -> window.getFileActions().close());
            dialogShots(out, prefix + "-close", Theme.Mode.DARK, Theme.Mode.LIGHT);
            closeDialog("Cancel");

            fx(() -> {
                window.menuItem("menu-help-about").fire();
                return null;
            });
            dialogShots(out, prefix + "-about", Theme.Mode.DARK);
            closeDialog("Close");

            fx(() -> {
                window.getNoticeBar().show("Imported onboarding.json with 8 warnings.", "Show warnings", () -> {
                }, false);
                window.getDialogs().showWarnings("Imported onboarding.json with 8 warnings.", List.of(
                        "Expressions are not supported (3 properties use their static value)",
                        "Layer effects are not supported: Gaussian Blur on \"glow\" was ignored",
                        "Track mattes of type luma are approximated with an alpha mask (2 layers)",
                        "Text is placed approximately: the baseline is estimated without the font (4 texts)",
                        "Merge paths are not supported (1 shape group)",
                        "Time remapping is not supported: \"loop\" plays at its own speed",
                        "Image asset img_3.png is missing from the file and was left out",
                        "Repeater offsets other than 0 are ignored (1 repeater)"));
                return null;
            });
            dialogShots(out, prefix + "-warnings", Theme.Mode.DARK, Theme.Mode.LIGHT);
            closeDialog("Close");
        } finally {
            fx(() -> {
                window.shutdown();
                window.getStage().hide();
                return null;
            });
            try (var files = Files.walk(config)) {
                files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private void dialogShots(Path out, String name, Theme.Mode... modes) throws Exception {
        for (Theme.Mode mode : modes) {
            fx(() -> {
                window.getTheme().setMode(mode);
                return null;
            });
            Thread.sleep(1200);
            settle();
            WritableImage image = fx(() -> {
                SnapshotParameters parameters = new SnapshotParameters();
                parameters.setTransform(Transform.scale(SCALE, SCALE));
                return window.getScene().getRoot().snapshot(parameters, null);
            });
            Path file = out.resolve(name + "-" + mode.name().toLowerCase() + ".png");
            Files.write(file, PngEncoder.encode(image));
            System.out.println("Wrote " + file);
        }
    }

    private void closeDialog(String buttonText) throws Exception {
        fx(() -> {
            window.getScene().getRoot().lookupAll(".dialog-pane > .content-pane .footer .button").stream()
                    .map(ButtonBase.class::cast).filter(b -> b.getText().equals(buttonText)).findFirst()
                    .orElseThrow().fire();
            return null;
        });
        settle();
    }

    /**
     * A card that shows what this editor can author: an orb with a drop shadow and a blur, a
     * gradient masked by a star, a wave drawn with the pen whose stroke is trimmed, a triangle on
     * a motion path, and a closed shape drawn with the pen.
     */
    private void buildScene() throws Exception {
        drag(ToolKind.RECTANGLE, 80, 60, 720, 540);
        fillHex("#1C232D");
        type(section(GeometrySection.class).cornerRadius().field(), "18");

        // drop shadow and blur
        drag(ToolKind.ELLIPSE, 130, 105, 260, 235);
        fillHex("#A56BFF");
        EffectsSection effects = section(EffectsSection.class);
        addEffect(EffectsSection.ADD_SHADOW_ID);
        type(number(effects, "effects[0].radius"), "22");
        type(number(effects, "effects[0].offsetY"), "12");
        addEffect(EffectsSection.ADD_BLUR_ID);
        type(number(effects, "effects[1].radius"), "3");
        orb = selected();

        // a gradient masked by a star
        drag(ToolKind.RECTANGLE, 310, 95, 470, 245);
        PaintField fill = section(FillSection.class).fill().field();
        fx(() -> {
            fill.getTypeBox().setValue(PaintField.LINEAR);
            return null;
        });
        settle();
        type(fill.getStopColorField().getHexField(), "#4C9AFF");
        Point2D lastStop = fx(() -> new Point2D(fill.getStopBar().offsetToX(1), 18));
        mouse(fill.getStopBar(), MouseEvent.MOUSE_PRESSED, lastStop.getX(), lastStop.getY(), 1);
        mouse(fill.getStopBar(), MouseEvent.MOUSE_RELEASED, lastStop.getX(), lastStop.getY(), 1);
        type(fill.getStopColorField().getHexField(), "#FFB454");
        type(fill.getStartField().getFirst(), "0");
        type(fill.getStartField().getSecond(), "0");
        type(fill.getEndField().getFirst(), "160");
        type(fill.getEndField().getSecond(), "150");
        masked = selected();
        drag(ToolKind.STAR, 315, 95, 465, 245);
        fx(() -> {
            doc.getSelection().layerIds().setAll(masked);
            return null;
        });
        settle();
        ComboBox<MaskSection.Choice> mask = lookup("#" + MaskSection.LAYER_ID);
        fx(() -> {
            mask.setValue(mask.getItems().stream().filter(c -> c.name().startsWith("Star")).findFirst().orElseThrow());
            return null;
        });
        settle();

        // a wave drawn with the pen; its stroke is trimmed
        tool(ToolKind.PEN);
        penPoint(130, 345);
        penCurve(215, 305, 255, 305);
        penCurve(320, 360, 360, 360);
        penCurve(425, 310, 465, 310);
        penPoint(520, 340);
        key(KeyCode.ENTER);
        wave = selected();
        StrokeSection stroke = section(StrokeSection.class);
        type(stroke.width().field(), "7");
        type(stroke.paint().field().getSolidField().getHexField(), "#FFB454");
        fire(lookup("#" + TrimSection.ENABLED_ID));
        TrimSection trim = section(TrimSection.class);
        scrub(0.2);
        fire(trim.end().toggle());
        type(trim.end().field(), "0");
        scrub(1.4);
        type(trim.end().field(), "100");

        // a triangle that travels along a motion path
        drag(ToolKind.POLYGON, 566, 96, 610, 140);
        fillHex("#4C9AFF");
        traveller = selected();
        fire(lookup("#" + MotionPathSection.ADD_ID));
        penPoint(588, 125);
        penCurve(655, 250, 655, 310);
        penCurve(590, 400, 560, 430);
        penPoint(600, 490);
        key(KeyCode.ENTER);
        fire(lookup("#" + MotionPathSection.ORIENT_ID));

        // a closed shape drawn with the pen
        tool(ToolKind.PEN);
        penCurve(250, 445, 250, 400);
        penCurve(350, 395, 405, 395);
        penPoint(500, 460);
        penCurve(360, 510, 305, 510);
        penPoint(250, 445);
        shape = selected();
        addEffect(EffectsSection.ADD_SHADOW_ID);
    }

    /** Path keyframes on the closed shape, so that it morphs, and the final state for the picture. */
    private void animate() throws Exception {
        Vec2 inside = fx(() -> canvas.documentToView(new Vec2(350, 455)));
        mouse(canvas, MouseEvent.MOUSE_PRESSED, inside.x(), inside.y(), 1);
        mouse(canvas, MouseEvent.MOUSE_RELEASED, inside.x(), inside.y(), 1);
        mouse(canvas, MouseEvent.MOUSE_PRESSED, inside.x(), inside.y(), 2);
        mouse(canvas, MouseEvent.MOUSE_RELEASED, inside.x(), inside.y(), 2);
        settle();
        if (!(fx(canvas::getModalTool) instanceof PathEditTool)) {
            throw new IllegalStateException("The double-click did not start path editing");
        }
        fire(lookup("#" + LayerTreeColumn.AUTO_KEY_ID));
        scrub(0.4);
        dragCanvas(500, 460, 515, 484);
        scrub(1.6);
        dragCanvas(515, 484, 505, 444);
        fire(lookup("#" + LayerTreeColumn.AUTO_KEY_ID));
        scrub(0.9);

        for (String id : List.of(shape, wave, traveller)) {
            Node cell = fx(() -> timeline.getLayerColumn().layerCell(id).orElseThrow());
            fire((ButtonBase) cell.lookup(".disclosure"));
        }
        for (Class<? extends SectionBase> type : List.of(LayerSection.class, TransformSection.class)) {
            fx(() -> {
                section(type).setCollapsed(true);
                return null;
            });
        }
        // two vertices selected, so their tangent handles show
        clickCanvas(350, 395, false);
        clickCanvas(360, 510, true);
        fx(() -> {
            canvas.requestFocus();
            return null;
        });
        settle();
    }

    /**
     * More pictures for looking at what the main ones do not show: the inspector for each of the
     * other layers, the motion path on the canvas, and the pen while a path is being drawn.
     */
    private void extras(Path out) throws Exception {
        Files.createDirectories(out);
        Node side = window.getSidePanel();
        snapshot(canvas, out.resolve("canvas-path-edit.png"));
        snapshot(timeline, out.resolve("timeline.png"));
        snapshot(side, out.resolve("inspector-path.png"));
        key(KeyCode.ESCAPE);
        for (Class<? extends SectionBase> type : List.of(GeometrySection.class, FillSection.class, StrokeSection.class)) {
            fx(() -> {
                section(type).setCollapsed(true);
                return null;
            });
        }
        String[][] shots = {{orb, "effects"}, {masked, "mask"}, {wave, "trim"}, {traveller, "motion-path"}};
        for (String[] shot : shots) {
            fx(() -> {
                doc.getSelection().layerIds().setAll(shot[0]);
                return null;
            });
            settle();
            snapshot(side, out.resolve("inspector-" + shot[1] + ".png"));
            snapshot(canvas, out.resolve("canvas-" + shot[1] + ".png"));
        }
        fire(lookup("#" + MotionPathSection.EDIT_ID));
        clickCanvas(655, 250, false);
        snapshot(canvas, out.resolve("canvas-motion-path-edit.png"));
        key(KeyCode.ESCAPE);

        // the mask layer itself, selected in the timeline: not drawn, but outlined
        String star = fx(() -> doc.getAnimation().layers().stream().filter(l -> l.name().startsWith("Star")).findFirst()
                .orElseThrow().id());
        fx(() -> {
            doc.getSelection().layerIds().setAll(star);
            return null;
        });
        settle();
        snapshot(canvas, out.resolve("canvas-mask-layer.png"));
        snapshot(side, out.resolve("inspector-mask-layer.png"));

        // the pen in the middle of a path
        tool(ToolKind.PEN);
        penPoint(140, 430);
        penCurve(190, 400, 220, 400);
        penCurve(225, 470, 225, 500);
        Vec2 hover = fx(() -> canvas.documentToView(new Vec2(150, 505)));
        mouse(canvas, MouseEvent.MOUSE_MOVED, hover.x(), hover.y(), 0);
        settle();
        snapshot(canvas, out.resolve("canvas-pen.png"));
        key(KeyCode.ESCAPE);
        fx(() -> {
            doc.undo();
            return null;
        });
        settle();
    }

    // ------------------------------------------------------------------ helpers

    private String selected() throws Exception {
        return fx(() -> doc.getSelection().layerIds().get(0));
    }

    @SuppressWarnings("unchecked")
    private <T extends Node> T lookup(String selector) throws Exception {
        T node = (T) fx(() -> window.getScene().lookup(selector));
        if (node == null) {
            throw new IllegalStateException("No node for " + selector);
        }
        return node;
    }

    private NumberField number(SectionBase section, String key) {
        return ((PropertyRows.NumberRow) section.propertyRow(key).orElseThrow()).field();
    }

    private void addEffect(String itemId) throws Exception {
        MenuButton add = lookup("#" + EffectsSection.ADD_ID);
        fx(() -> {
            add.getItems().stream().filter(i -> itemId.equals(i.getId())).findFirst().orElseThrow().fire();
            return null;
        });
        settle();
    }

    private void tool(ToolKind tool) throws Exception {
        fx(() -> {
            canvas.setActiveTool(tool);
            return null;
        });
    }

    /** A click with the tool in charge: a corner point of the pen. */
    private void penPoint(double x, double y) throws Exception {
        clickCanvas(x, y, false);
    }

    /** Press and drag with the tool in charge: a smooth point of the pen whose handle ends at the second point. */
    private void penCurve(double x, double y, double handleX, double handleY) throws Exception {
        dragCanvas(x, y, handleX, handleY);
    }

    private void clickCanvas(double x, double y, boolean shift) throws Exception {
        Vec2 at = fx(() -> canvas.documentToView(new Vec2(x, y)));
        mouse(canvas, MouseEvent.MOUSE_PRESSED, at.x(), at.y(), 1, shift);
        mouse(canvas, MouseEvent.MOUSE_RELEASED, at.x(), at.y(), 1, shift);
        settle();
    }

    private void dragCanvas(double fromX, double fromY, double toX, double toY) throws Exception {
        Vec2 from = fx(() -> canvas.documentToView(new Vec2(fromX, fromY)));
        Vec2 to = fx(() -> canvas.documentToView(new Vec2(toX, toY)));
        mouse(canvas, MouseEvent.MOUSE_PRESSED, from.x(), from.y(), 1);
        for (int i = 1; i <= 4; i++) {
            Vec2 at = from.lerp(to, i / 4.0);
            mouse(canvas, MouseEvent.MOUSE_DRAGGED, at.x(), at.y(), 1);
        }
        mouse(canvas, MouseEvent.MOUSE_RELEASED, to.x(), to.y(), 1);
        settle();
    }

    private void key(KeyCode code) throws Exception {
        fx(() -> {
            Event.fireEvent(canvas, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
            Event.fireEvent(canvas, new KeyEvent(KeyEvent.KEY_RELEASED, "", "", code, false, false, false, false));
            return null;
        });
        settle();
    }

    private void snapshot(Node node, Path file) throws Exception {
        WritableImage image = fx(() -> {
            SnapshotParameters parameters = new SnapshotParameters();
            parameters.setTransform(Transform.scale(SCALE, SCALE));
            return node.snapshot(parameters, null);
        });
        Files.write(file, PngEncoder.encode(image));
    }

    private <S extends InspectorSection> S section(Class<S> type) {
        return inspector.section(type).orElseThrow();
    }

    private void fillHex(String hex) throws Exception {
        type(section(FillSection.class).fill().field().getSolidField().getHexField(), hex);
    }

    private void type(TextField field, String text) throws Exception {
        fx(() -> {
            field.requestFocus();
            field.setText(text);
            Event.fireEvent(field, new ActionEvent());
            return null;
        });
        settle();
    }

    private void fire(ButtonBase button) throws Exception {
        fx(() -> {
            button.fire();
            return null;
        });
        settle();
    }

    /** Drags the playhead in the ruler to a time. */
    private void scrub(double time) throws Exception {
        Node ruler = timeline.getRuler();
        double x = fx(() -> timeline.getScale().toX(time));
        mouse(ruler, MouseEvent.MOUSE_PRESSED, x, 8, 1);
        mouse(ruler, MouseEvent.MOUSE_RELEASED, x, 8, 1);
        settle();
    }

    private void drag(ToolKind tool, double fromX, double fromY, double toX, double toY) throws Exception {
        tool(tool);
        dragCanvas(fromX, fromY, toX, toY);
    }

    private void mouse(Node node, EventType<MouseEvent> type, double x, double y, int clicks) throws Exception {
        mouse(node, type, x, y, clicks, false);
    }

    private void mouse(Node node, EventType<MouseEvent> type, double x, double y, int clicks, boolean shift)
            throws Exception {
        fx(() -> {
            Point2D scene = node.localToScene(x, y);
            boolean down = type == MouseEvent.MOUSE_PRESSED || type == MouseEvent.MOUSE_DRAGGED;
            Event.fireEvent(node, new MouseEvent(type, scene.getX(), scene.getY(), scene.getX(), scene.getY(),
                    type == MouseEvent.MOUSE_MOVED ? MouseButton.NONE : MouseButton.PRIMARY, clicks,
                    shift, false, false, false, down, false, false, false, false, false, null));
            return null;
        });
    }

    private void settle() throws Exception {
        WaitForAsyncUtils.waitForFxEvents();
        Thread.sleep(150);
        WaitForAsyncUtils.waitForFxEvents();
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        return WaitForAsyncUtils.asyncFx(action).get();
    }
}
