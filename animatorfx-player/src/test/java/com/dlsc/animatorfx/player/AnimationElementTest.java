package com.dlsc.animatorfx.player;

import com.dlsc.animatorfx.model.Animatable;
import com.dlsc.animatorfx.model.Layer;
import com.dlsc.animatorfx.model.Paint;
import com.dlsc.animatorfx.model.Rgba;
import com.dlsc.animatorfx.model.ShapeLayer;
import com.dlsc.animatorfx.model.TextLayer;
import com.dlsc.animatorfx.model.Transform;
import com.dlsc.animatorfx.model.Vec2;
import com.dlsc.animatorfx.model.io.AnimationFile;
import javafx.event.Event;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Path;
import javafx.scene.text.Text;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.dlsc.animatorfx.player.TestDocs.*;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(FxThreadExtension.class)
class AnimationElementTest {

    private final AnimationView view = new AnimationView(new AnimationPlayer(new ManualClock()));

    private static Layer exported(Layer layer, String name) {
        return layer.withBase(layer.base().withExportName(name));
    }

    private static AnimationFile file(Layer... layers) {
        return new AnimationFile(doc(layers), Map.of());
    }

    private void show() {
        if (view.getScene() == null) new Scene(new Pane(view));
        view.applyCss();
        view.resize(200, 100);
        view.layout();
    }

    private static Path outline(Node node) {
        return (Path) ((Group) node).getChildren().getFirst();
    }

    private static void click(Node node) {
        Event.fireEvent(node, new MouseEvent(MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0, MouseButton.PRIMARY,
                1, false, false, false, false, false, false, false, false, false, false, null));
    }

    @Test
    void nestedHandleConfiguredBeforeCssAttachesInteractionsWhenRendered() {
        view.setAnimation(file(group("g", exported(rect("r", 10, 10), "submit"))));
        AnimationElement element = view.getElement("submit");
        AtomicInteger clicks = new AtomicInteger();
        element.setOnMouseClicked(event -> clicks.incrementAndGet());
        assertNull(element.getNode());
        assertSame(element, view.getElement("submit"));

        show();
        assertSame(view.getLayerNode("r"), element.getNode());
        click(element.getNode());
        assertEquals(1, clicks.get());
        element.setOnMouseClicked(null);
        click(element.getNode());
        assertEquals(1, clicks.get());
    }

    @Test
    void fillOverrideWinsDuringSeekingAndClearingRestoresCurrentSample() {
        ShapeLayer shape = rect("r", 10, 10).withFill(keys(
                0, new Paint.Solid(Rgba.BLACK), 2, new Paint.Solid(Rgba.WHITE)));
        shape = transformed(shape, Transform.IDENTITY.withPosition(keys(0, Vec2.ZERO, 2, new Vec2(100, 0))));
        view.setAnimation(file(exported(shape, "accent")));
        AnimationElement element = view.getElement("accent");
        element.setFill(Color.RED);
        show();

        view.seek(1);
        assertEquals(Color.RED, outline(element.getNode()).getFill());
        assertEquals(50, element.getNode().getTransforms().getFirst().getTx(), 1e-9);
        element.setFill(null);
        assertEquals(Color.color(0.5, 0.5, 0.5), outline(element.getNode()).getFill());
        element.setFill(Color.BLUE);
        element.clearOverrides();
        assertEquals(Color.color(0.5, 0.5, 0.5), outline(element.getNode()).getFill());
    }

    @Test
    void textAndVisibilityOverridesRestoreAuthoredValuesWhenCleared() {
        TextLayer text = new TextLayer(base("t"), "Authored", "System", null, Animatable.of(12.0),
                TextLayer.Weight.NORMAL, false, TextLayer.Align.CENTER,
                Animatable.of(new Paint.Solid(Rgba.BLACK)), null);
        view.setAnimation(file(exported(text, "greeting")));
        AnimationElement element = view.getElement("greeting");
        element.setText("Welcome, Anna");
        element.setVisible(false);
        show();

        Text node = (Text) element.getNode();
        assertEquals("Welcome, Anna", node.getText());
        assertEquals(-node.getLayoutBounds().getWidth() / 2, node.getX(), 1e-9);
        assertFalse(node.isVisible());
        view.seek(1);
        assertFalse(node.isVisible());
        element.clearOverrides();
        assertEquals("Authored", node.getText());
        assertTrue(node.isVisible());
        assertEquals(-node.getLayoutBounds().getWidth() / 2, node.getX(), 1e-9);
    }

    @Test
    void handlesDetachOldNodesAndFollowDocumentAndSkinReplacement() {
        view.setAnimation(file(exported(rect("r", 10, 10), "accent")));
        AnimationElement element = view.getElement("accent");
        AtomicInteger clicks = new AtomicInteger();
        AtomicInteger changes = new AtomicInteger();
        element.nodeProperty().addListener(observable -> changes.incrementAndGet());
        element.setOnMouseClicked(event -> clicks.incrementAndGet());
        element.setFill(Color.RED);
        show();
        Node old = element.getNode();

        view.setAnimation(file(exported(rect("replacement", 20, 20), "accent")));
        Node replacement = element.getNode();
        assertNotSame(old, replacement);
        assertEquals(Color.RED, outline(replacement).getFill());
        click(old);
        assertEquals(0, clicks.get());
        click(replacement);
        assertEquals(1, clicks.get());

        view.setSkin(new AnimationViewSkin(view));
        assertNotSame(replacement, element.getNode());
        click(replacement);
        assertEquals(1, clicks.get());
        click(element.getNode());
        assertEquals(2, clicks.get());
        assertEquals(Color.RED, outline(element.getNode()).getFill());

        view.setAnimation(null);
        assertNull(element.getNode());
        view.setAnimation(file(exported(rect("new", 10, 10), "accent")));
        assertSame(element, view.getElement("accent"));
        assertEquals(Color.RED, outline(element.getNode()).getFill());
        assertTrue(changes.get() >= 4);
    }

    @Test
    void lookupRejectsMissingExportsAndUnsupportedCustomization() {
        assertThrows(IllegalStateException.class, () -> view.getElement("missing"));
        view.setAnimation(file(exported(rect("r", 10, 10), "accent")));
        assertThrows(IllegalArgumentException.class, () -> view.getElement("r"));
        assertThrows(IllegalArgumentException.class, () -> view.getElement("missing"));
        assertThrows(IllegalArgumentException.class, () -> view.getElement("accent").setText("Not text"));
        view.setAnimation(file(exported(group("g"), "panel")));
        assertThrows(IllegalArgumentException.class, () -> view.getElement("panel").setFill(Color.RED));
    }

    @Test
    void visibilityOverrideCanShowAnInactiveLayerAndClearingRestoresItsRange() {
        Layer layer = exported(rect("r", 10, 10), "accent");
        layer = layer.withBase(layer.base().withInPoint(1));
        view.setAnimation(file(layer));
        AnimationElement element = view.getElement("accent");
        show();
        assertFalse(element.getNode().isVisible());
        element.setVisible(true);
        assertTrue(element.getNode().isVisible());
        view.seek(0.5);
        assertTrue(element.getNode().isVisible());
        element.setVisible(null);
        assertFalse(element.getNode().isVisible());
        view.seek(1.5);
        assertTrue(element.getNode().isVisible());
    }

    @Test
    void removingOrRenamingAnExportDoesNotLeakOverridesIntoReusedNodes() {
        Layer original = exported(rect("r", 10, 10), "accent");
        view.setAnimation(file(original));
        AnimationElement accent = view.getElement("accent");
        accent.setFill(Color.RED);
        show();
        Node node = accent.getNode();

        view.setAnimation(file(original.withBase(original.base().withExportName(""))));
        assertNull(accent.getNode());
        assertSame(node, view.getLayerNode("r"));
        assertEquals(Color.WHITE, outline(node).getFill());

        view.setAnimation(file(original.withBase(original.base().withExportName("other"))));
        AnimationElement other = view.getElement("other");
        assertEquals(Color.WHITE, outline(other.getNode()).getFill());
        other.setFill(Color.BLUE);
        view.setAnimation(file(original));
        assertNull(other.getNode());
        assertEquals(Color.RED, outline(accent.getNode()).getFill());
    }

    @Test
    void nodeListenersCanLookUpOtherElementsDuringReplacement() {
        view.setAnimation(file(exported(rect("r", 10, 10), "first"), exported(rect("s", 10, 10), "second"),
                exported(rect("t", 10, 10), "third")));
        AnimationElement first = view.getElement("first");
        view.getElement("third");
        show();
        first.nodeProperty().addListener((observable, before, now) -> {
            if (now != null) view.getElement("second").setFill(Color.BLUE);
        });

        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread thread = Thread.currentThread();
        Thread.UncaughtExceptionHandler before = thread.getUncaughtExceptionHandler();
        thread.setUncaughtExceptionHandler((failedThread, failure) -> error.set(failure));
        try {
            view.setAnimation(file(exported(rect("new-r", 10, 10), "first"),
                    exported(rect("new-s", 10, 10), "second"), exported(rect("new-t", 10, 10), "third")));
        } finally {
            thread.setUncaughtExceptionHandler(before);
        }
        assertNull(error.get());
        assertSame(view.getLayerNode("new-r"), first.getNode());
        assertEquals(Color.BLUE, outline(view.getElement("second").getNode()).getFill());
    }
}
