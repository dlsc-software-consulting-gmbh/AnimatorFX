package com.dlsc.animatorfx.editor.inspector.fields;

import com.dlsc.animatorfx.editor.doc.Commands;
import com.dlsc.animatorfx.editor.inspector.DocumentView;
import com.dlsc.animatorfx.editor.inspector.sections.FillSection;
import com.dlsc.animatorfx.editor.inspector.sections.EffectsSection;
import com.dlsc.animatorfx.editor.ui.EditorUiTest;
import com.dlsc.animatorfx.editor.ui.SidePanel;
import com.dlsc.animatorfx.model.Animatable;
import com.dlsc.animatorfx.model.Effect;
import com.dlsc.animatorfx.model.Paint;
import com.dlsc.animatorfx.model.Rgba;
import com.dlsc.animatorfx.model.Vec2;
import javafx.event.Event;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.testfx.util.WaitForAsyncUtils;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Native pixel tests require a desktop compositor; headless tests cover cancellation and cleanup. */
class ColorPipetteTest extends EditorUiTest {

    private Stage sampleWindow;
    private int sampleWindowClicks;
    private static final Rgba SAMPLE = Rgba.parse("#3366CC");

    @AfterEach
    void closeSampleWindow() {
        run(() -> {
            if (sampleWindow != null) sampleWindow.close();
        });
    }

    private PaintField fill() {
        return window.getSidePanel().getInspector().section(FillSection.class).orElseThrow().fill().field();
    }

    private void showSampleWindow() {
        run(() -> {
            Pane content = new Pane();
            content.setBackground(new Background(new BackgroundFill(Color.web("#3366CC"),
                    CornerRadii.EMPTY, null)));
            content.setOnMousePressed(event -> sampleWindowClicks++);
            sampleWindow = new Stage(StageStyle.UNDECORATED);
            sampleWindow.initOwner(window.getStage());
            sampleWindow.setAlwaysOnTop(true);
            sampleWindow.setScene(new Scene(content, 180, 180));
            sampleWindow.setX(40);
            sampleWindow.setY(60);
            sampleWindow.show();
        });
        WaitForAsyncUtils.waitForFxEvents();
    }

    private List<Window> overlays() {
        return fx(() -> Window.getWindows().stream()
                .filter(w -> w.getScene() != null
                        && w.getScene().getRoot().getStyleClass().contains("color-pipette-overlay"))
                .toList());
    }

    private void pick(ColorField field) throws Exception {
        pick(field, false);
    }

    private void pick(ColorField field, boolean enter) throws Exception {
        var before = doc.getAnimation();
        if (sampleWindow != null) run(sampleWindow::close);
        showSampleWindow();
        Point2D point = fx(() -> sampleWindow.getScene().getRoot().localToScreen(50, 50));
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
                () -> fx(() -> new javafx.scene.robot.Robot().getPixelColor(point).equals(Color.web("#3366CC"))));
        run(pipette(field)::fire);
        Window overlay = overlays().stream().filter(w -> point.getX() >= w.getX()
                && point.getX() < w.getX() + w.getWidth() && point.getY() >= w.getY()
                && point.getY() < w.getY() + w.getHeight()).findFirst().orElseThrow();
        Node surface = overlay.getScene().getRoot();
        Point2D local = fx(() -> surface.screenToLocal(point));
        if (enter) {
            Point2D previous = fx(() -> new javafx.scene.robot.Robot().getMousePosition());
            try {
                run(() -> {
                    new javafx.scene.robot.Robot().mouseMove(point);
                    Event.fireEvent(surface, new KeyEvent(KeyEvent.KEY_PRESSED, "", "",
                            KeyCode.ENTER, false, false, false, false));
                });
                WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
                        () -> fx(() -> doc.getAnimation() != before));
            } finally {
                run(() -> new javafx.scene.robot.Robot().mouseMove(previous));
            }
        } else {
            click(surface, local.getX(), local.getY());
        }
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
                () -> fx(() -> doc.getAnimation() != before));
        layout();
    }

    @Test
    @DisabledIfSystemProperty(named = "glass.platform", matches = "Headless|Monocle")
    void mixedShadowColorsKeepEachSelectedLayersAlpha() throws Exception {
        add(rect("a", 100, 100, 100, 100), rect("b", 300, 100, 100, 100));
        for (String id : List.of("a", "b")) {
            Rgba color = Rgba.parse(id.equals("a") ? "#FF000040" : "#00FF0080");
            execute(Commands.addEffect(id, new Effect.DropShadow(Animatable.of(color), Animatable.of(12.0),
                    Animatable.of(0.0), Animatable.of(0.0), Animatable.of(0.0))));
        }
        select("a", "b");
        ColorField field = (ColorField) window.getSidePanel().getInspector()
                .section(EffectsSection.class).orElseThrow().node().lookup(".color-field");
        var before = doc.getAnimation();
        pick(field);
        assertEquals(Rgba.parse("#3366CC40"), value("a", "effects[0].color").at(0));
        assertEquals(Rgba.parse("#3366CC80"), value("b", "effects[0].color").at(0));
        run(doc::undo);
        assertEquals(before, doc.getAnimation());
    }

    @Test
    @DisabledIfSystemProperty(named = "glass.platform", matches = "Headless|Monocle")
    void mixedSolidPaintsKeepEachSelectedLayersAlpha() throws Exception {
        add(rect("a", 100, 100, 100, 100), rect("b", 300, 100, 100, 100));
        execute(Commands.setProperty("a", "fill", Animatable.of(new Paint.Solid(Rgba.parse("#FF000040")))));
        execute(Commands.setProperty("b", "fill", Animatable.of(new Paint.Solid(Rgba.parse("#00FF0080")))));
        select("a", "b");
        var before = doc.getAnimation();
        pick(fill().getSolidField());
        assertEquals(new Paint.Solid(Rgba.parse("#3366CC40")), value("a", "fill").at(0));
        assertEquals(new Paint.Solid(Rgba.parse("#3366CC80")), value("b", "fill").at(0));
        run(doc::undo);
        assertEquals(before, doc.getAnimation());
    }

    @Test
    @DisabledIfSystemProperty(named = "glass.platform", matches = "Headless|Monocle")
    void enterPicksThePixelUnderThePointerWithoutClickingTheOtherWindow() throws Exception {
        add(rect("a", 100, 100, 100, 100));
        select("a");
        ColorField field = fill().getSolidField();
        pick(field, true);
        assertTrue(overlays().isEmpty());
        assertEquals(0, sampleWindowClicks);
        assertEquals(new Paint.Solid(SAMPLE), value("a", "fill").at(0));
        run(doc::undo);
        assertEquals(new Paint.Solid(Rgba.WHITE), value("a", "fill").at(0));
    }

    @Test
    void closingTheOwnerWindowCancelsPickingAndRemovesAllOverlays() {
        add(rect("a", 100, 100, 100, 100));
        select("a");
        var before = doc.getAnimation();
        run(pipette(fill().getSolidField())::fire);
        assertFalse(overlays().isEmpty());
        run(() -> window.getStage().hide());
        assertTrue(overlays().isEmpty());
        assertEquals(before, doc.getAnimation());
    }

    private Button pipette(ColorField field) {
        Button button = fx(() -> (Button) field.lookup(".pipette"));
        assertNotNull(button, "Every color field needs a pipette button");
        return button;
    }

    @Test
    @DisabledIfSystemProperty(named = "glass.platform", matches = "Headless|Monocle")
    void pipetteSamplesAnotherWindowPreservesAlphaAndCreatesOneUndoStep() throws Exception {
        add(rect("a", 100, 100, 100, 100));
        Rgba before = Rgba.parse("#FFFFFF66");
        execute(Commands.setProperty("a", "fill", Animatable.of(new Paint.Solid(before))));
        select("a");
        ColorField field = fill().getSolidField();
        run(field::showPicker);
        pick(field);

        assertFalse(field.getPopup().isShowing());
        assertTrue(overlays().isEmpty());
        assertEquals(Rgba.parse("#3366CC66"), ((Paint.Solid) value("a", "fill").at(0)).color());
        assertEquals("#3366CC66", fx(() -> field.getHexField().getText()));
        run(doc::undo);
        assertEquals(new Paint.Solid(before), value("a", "fill").at(0));
        run(doc::redo);
        assertEquals(Rgba.parse("#3366CC66"), ((Paint.Solid) value("a", "fill").at(0)).color());
    }

    @Test
    void escapeAndDocumentChangesCancelPickingWithoutChangingTheColor() {
        add(rect("a", 100, 100, 100, 100));
        select("a");
        ColorField field = fill().getSolidField();
        var before = doc.getAnimation();
        run(pipette(field)::fire);
        Node overlay = overlays().getFirst().getScene().getRoot();
        run(() -> Event.fireEvent(overlay, new KeyEvent(KeyEvent.KEY_PRESSED, "", "",
                KeyCode.ESCAPE, false, false, false, false)));
        assertTrue(overlays().isEmpty());
        assertEquals(before, doc.getAnimation());

        run(pipette(field)::fire);
        run(() -> window.getFileActions().newDocument());
        assertTrue(overlays().isEmpty());
        assertEquals(before, doc.getAnimation());
    }

    @Test
    @DisabledIfSystemProperty(named = "glass.platform", matches = "Headless|Monocle")
    void gradientStopsAndDocumentBackgroundUseTheSamePipette() throws Exception {
        add(rect("a", 100, 100, 100, 100));
        Paint gradient = new Paint.LinearGradient(Vec2.ZERO, new Vec2(100, 0),
                List.of(new Paint.Stop(0, Rgba.parse("#FF000080")), new Paint.Stop(1, Rgba.WHITE)));
        execute(Commands.setProperty("a", "fill", Animatable.of(gradient)));
        select("a");
        pick(fill().getStopColorField());
        Paint.LinearGradient changed = (Paint.LinearGradient) value("a", "fill").at(0);
        assertEquals(Rgba.parse("#3366CC80"), changed.stops().getFirst().color());
        assertEquals(new Paint.Stop(1, Rgba.WHITE), changed.stops().getLast());
        run(doc::undo);
        assertEquals(gradient, value("a", "fill").at(0));

        execute(Commands.setDocument("Background", a -> a.withBackground(Rgba.WHITE)));
        run(() -> window.getSidePanel().setActiveTab(SidePanel.DOCUMENT));
        layout();
        ColorField background = (ColorField) window.getSidePanel().getDocumentView()
                .lookup("#" + DocumentView.BACKGROUND_ID);
        pick(background);
        assertEquals(SAMPLE, doc.getAnimation().background());
        run(doc::undo);
        assertEquals(Rgba.WHITE, doc.getAnimation().background());
    }
}
