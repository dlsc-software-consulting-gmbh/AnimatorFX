package com.dlsc.animatorfx.player;

import com.dlsc.animatorfx.model.Layer;
import com.dlsc.animatorfx.model.ShapeLayer;
import com.dlsc.animatorfx.model.TextLayer;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleObjectProperty;
import javafx.event.EventHandler;
import javafx.scene.Node;
import javafx.scene.input.MouseEvent;
import javafx.scene.paint.Paint;

import java.util.Objects;

/**
 * A live, application-facing element obtained from {@link AnimationView#getElement(String)}.
 * The handle follows its export name across document and skin replacements. Configure it
 * before the first CSS pass if needed; its node is available only when rendered.
 * <p>
 * Text, fill and visibility overrides win over authored values until set to {@code null} or
 * cleared with {@link #clearOverrides()}. Other properties continue to follow the animation.
 * Overrides are retained while the export is absent and applied again when it returns.
 * A replacement of a different layer type ignores overrides it cannot support.
 * <p>
 * Use on the JavaFX application thread only. Do not reparent the node or bind properties owned
 * by the renderer; use slots for application-owned controls.
 */
public final class AnimationElement {

    private final String exportName;
    private final Runnable refresh;
    private final ReadOnlyObjectWrapper<Node> node = new ReadOnlyObjectWrapper<>(this, "node");
    private final ObjectProperty<EventHandler<MouseEvent>> onMouseClicked =
            new SimpleObjectProperty<>(this, "onMouseClicked");
    private EventHandler<MouseEvent> attachedHandler;
    private Layer layer;
    private Layer lastLayer;
    private String text;
    private Paint fill;
    private Boolean visible;

    AnimationElement(String exportName, Layer layer, Runnable refresh) {
        this.exportName = exportName;
        this.layer = layer;
        lastLayer = layer;
        this.refresh = refresh;
        onMouseClicked.addListener(observable -> installHandler());
    }

    /** The unique, application-facing name, not the layer's display name or internal id. */
    public String getExportName() {
        return exportName;
    }

    /**
     * The current rendered node, or {@code null} without a skin or while the export is absent.
     * Listen here to reinstall custom interactions such as tooltips when the node changes.
     * A shape's node is a group, not necessarily a {@code Shape}.
     *
     * @return the read-only node property
     */
    public final ReadOnlyObjectProperty<Node> nodeProperty() {
        return node.getReadOnlyProperty();
    }

    public final Node getNode() {
        return node.get();
    }

    /**
     * The click handler, installed on the current node and reinstalled on replacements.
     * Setting {@code null} removes this handle's handler. Unlike an interaction attached
     * directly to a node, this registration survives renderer rebuilds.
     *
     * @return the click handler property
     */
    public final ObjectProperty<EventHandler<MouseEvent>> onMouseClickedProperty() {
        return onMouseClicked;
    }

    public final EventHandler<MouseEvent> getOnMouseClicked() {
        return onMouseClicked.get();
    }

    public final void setOnMouseClicked(EventHandler<MouseEvent> value) {
        onMouseClicked.set(value);
    }

    /** The text override, or {@code null} to use the document's text. */
    public String getText() {
        return text;
    }

    /**
     * Overrides the text of a text layer, preserving its authored alignment.
     *
     * @param value replacement text, or {@code null} to clear the override
     * @throws IllegalArgumentException if the element is not a text layer
     */
    public void setText(String value) {
        if (value != null && !(lastLayer instanceof TextLayer)) {
            throw new IllegalArgumentException("Element is not a text layer: " + exportName);
        }
        if (!Objects.equals(text, value)) {
            text = value;
            refresh.run();
        }
    }

    /** The fill override, or {@code null} to use the document's sampled fill. */
    public Paint getFill() {
        return fill;
    }

    /**
     * Overrides the fill of a shape or text layer without changing strokes or geometry.
     *
     * @param value replacement fill, or {@code null} to clear the override
     * @throws IllegalArgumentException if the element is neither a shape nor a text layer
     */
    public void setFill(Paint value) {
        if (value != null && !(lastLayer instanceof ShapeLayer || lastLayer instanceof TextLayer)) {
            throw new IllegalArgumentException("Element does not support a fill: " + exportName);
        }
        if (!Objects.equals(fill, value)) {
            fill = value;
            refresh.run();
        }
    }

    /** The visibility override, or {@code null} to use the document's sampled visibility. */
    public Boolean getVisible() {
        return visible;
    }

    /**
     * Overrides the layer's own visibility, including its in/out range. Hidden parents, clips
     * and zero opacity can still prevent it from being seen.
     *
     * @param value replacement visibility, or {@code null} to clear the override
     */
    public void setVisible(Boolean value) {
        if (!Objects.equals(visible, value)) {
            visible = value;
            refresh.run();
        }
    }

    /** Restores authored text, fill and visibility immediately, without removing interactions. */
    public void clearOverrides() {
        text = null;
        fill = null;
        visible = null;
        refresh.run();
    }

    Layer layer() {
        return layer;
    }

    void setLayer(Layer value) {
        if (layer != value) {
            attach(null);
            layer = value;
            if (value != null) lastLayer = value;
        }
    }

    void attach(Node value) {
        if (node.get() == value) return;
        removeHandler();
        node.set(value);
        installHandler();
    }

    private void removeHandler() {
        Node current = node.get();
        if (current != null && attachedHandler != null && current.getOnMouseClicked() == attachedHandler) {
            current.setOnMouseClicked(null);
        }
        attachedHandler = null;
    }

    private void installHandler() {
        removeHandler();
        Node current = node.get();
        if (current != null && getOnMouseClicked() != null) {
            attachedHandler = getOnMouseClicked();
            current.setOnMouseClicked(attachedHandler);
        }
    }
}
