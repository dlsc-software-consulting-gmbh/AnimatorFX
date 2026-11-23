package com.dlsc.animatorfx.editor.ui;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The column of tool buttons at the left edge. The buttons have the ids {@code tool-select},
 * {@code tool-rectangle} and so on.
 */
public final class ToolRail extends VBox {

    private final ObjectProperty<ToolKind> activeTool = new SimpleObjectProperty<>(this, "activeTool", ToolKind.SELECT);
    private final Map<ToolKind, ToggleButton> buttons = new EnumMap<>(ToolKind.class);

    public ToolRail() {
        getStyleClass().add("tool-rail");
        for (ToolKind kind : ToolKind.values()) {
            FontIcon icon = new FontIcon(kind.icon());
            if (kind == ToolKind.SELECT) {
                // the navigation arrow, mirrored, reads as a pointer
                icon.setScaleX(-1);
            }
            ToggleButton button = new ToggleButton(null, icon);
            button.setId("tool-" + kind.name().toLowerCase(Locale.ROOT));
            button.getStyleClass().add("tool-button");
            button.setFocusTraversable(false);
            String shortcut = kind.shortcut() == null ? "" : " (" + kind.shortcut().getName() + ")";
            button.setTooltip(new Tooltip(kind.displayName() + shortcut));
            // a tool stays active when its button is clicked again
            button.setOnAction(e -> {
                setActiveTool(kind);
                button.setSelected(true);
            });
            buttons.put(kind, button);
            getChildren().add(button);
        }
        activeTool.addListener((obs, oldTool, newTool) -> updateButtons());
        updateButtons();
    }

    /**
     * The tool that the canvas uses for pointer gestures.
     *
     * @return the active tool property
     */
    public final ObjectProperty<ToolKind> activeToolProperty() {
        return activeTool;
    }

    public final ToolKind getActiveTool() {
        return activeTool.get();
    }

    public final void setActiveTool(ToolKind value) {
        activeTool.set(Objects.requireNonNull(value, "tool"));
    }

    private void updateButtons() {
        buttons.forEach((kind, button) -> button.setSelected(kind == getActiveTool()));
    }
}
