package com.dlsc.animatorfx.editor.ui;

import javafx.scene.input.KeyCode;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.material2.Material2AL;
import org.kordamp.ikonli.material2.Material2MZ;
import org.kordamp.ikonli.material2.Material2OutlinedAL;
import org.kordamp.ikonli.material2.Material2OutlinedMZ;

import java.util.Optional;

/**
 * The tools of the tool rail, in display order.
 */
public enum ToolKind {

    SELECT("Select", KeyCode.V, Material2OutlinedMZ.NEAR_ME),
    RECTANGLE("Rectangle", KeyCode.R, Material2AL.CROP_DIN),
    ELLIPSE("Ellipse", KeyCode.O, Material2MZ.PANORAMA_FISH_EYE),
    LINE("Line", KeyCode.L, Material2AL.HORIZONTAL_RULE),
    POLYGON("Polygon", KeyCode.Y, Material2OutlinedAL.CHANGE_HISTORY),
    STAR("Star", KeyCode.S, Material2MZ.STAR_BORDER),
    PEN("Pen", KeyCode.P, Material2AL.GESTURE),
    TEXT("Text", KeyCode.T, Material2MZ.TITLE),
    IMAGE("Image", KeyCode.I, Material2OutlinedAL.IMAGE),
    VIDEO("Video", null, Material2MZ.MOVIE),
    SLOT("Slot", KeyCode.K, Material2OutlinedMZ.WIDGETS);

    private final String displayName;
    private final KeyCode shortcut;
    private final Ikon icon;

    ToolKind(String displayName, KeyCode shortcut, Ikon icon) {
        this.displayName = displayName;
        this.shortcut = shortcut;
        this.icon = icon;
    }

    public String displayName() {
        return displayName;
    }

    /**
     * The key that selects the tool when pressed without modifiers, or null if none is assigned.
     */
    public KeyCode shortcut() {
        return shortcut;
    }

    public Ikon icon() {
        return icon;
    }

    public static Optional<ToolKind> forShortcut(KeyCode code) {
        for (ToolKind kind : values()) {
            if (kind.shortcut != null && kind.shortcut == code) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }
}
