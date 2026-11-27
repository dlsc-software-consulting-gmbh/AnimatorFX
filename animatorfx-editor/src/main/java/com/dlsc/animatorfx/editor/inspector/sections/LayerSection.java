package com.dlsc.animatorfx.editor.inspector.sections;

import com.dlsc.animatorfx.editor.doc.PropertyUnits;
import com.dlsc.animatorfx.editor.inspector.InspectorContext;
import com.dlsc.animatorfx.editor.inspector.SectionBase;
import com.dlsc.animatorfx.editor.inspector.fields.NumberField;
import com.dlsc.animatorfx.editor.inspector.fields.TextValueField;
import com.dlsc.animatorfx.editor.timeline.TimeScale;
import com.dlsc.animatorfx.model.Layer;
import com.dlsc.animatorfx.model.Layers;
import java.util.Optional;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.material2.Material2AL;
import org.kordamp.ikonli.material2.Material2MZ;

/**
 * Name, application export name, in and out point, visibility and lock of the selected layers.
 */
public final class LayerSection extends SectionBase {

    public static final String NAME_ID = "inspector-layer-name";
    public static final String EXPORT_NAME_ID = "inspector-layer-export-name";
    public static final String IN_ID = "inspector-layer-in";
    public static final String OUT_ID = "inspector-layer-out";
    public static final String VISIBLE_ID = "inspector-layer-visible";
    public static final String LOCKED_ID = "inspector-layer-locked";

    private final TextValueField name = new TextValueField();
    private final TextValueField exportName = new TextValueField();
    private final NumberField in = new NumberField(PropertyUnits.SECONDS);
    private final NumberField out = new NumberField(PropertyUnits.SECONDS);
    private final FontIcon visibleIcon = new FontIcon(Material2MZ.VISIBILITY);
    private final FontIcon lockedIcon = new FontIcon(Material2AL.LOCK_OPEN);
    private final ToggleButton visible = new ToggleButton(null, visibleIcon);
    private final ToggleButton locked = new ToggleButton(null, lockedIcon);
    private boolean updating;

    public LayerSection() {
        super("Layer");
        name.setId(NAME_ID);
        name.setValidator(text -> text.isBlank() ? java.util.Optional.of("A layer needs a name") : java.util.Optional.empty());
        name.setOnCommit(text -> context().edit("Rename Layer", Layer.class,
                l -> l.withBase(l.base().withName(text.strip())), null));
        row("Name", name);

        exportName.setId(EXPORT_NAME_ID);
        exportName.setPromptText("Private");
        exportName.setValidator(text -> {
            String value = text.strip();
            if (value.isEmpty()) return Optional.empty();
            if (context() == null || context().layers().size() != 1) return Optional.empty();
            String id = context().layers().getFirst().id();
            boolean used = Layers.flatten(context().animation()).stream()
                    .anyMatch(layer -> !layer.id().equals(id) && layer.base().exportName().equals(value));
            return used ? Optional.of("Export names must be unique throughout the animation") : Optional.empty();
        });
        exportName.setOnCommit(text -> {
            if (context().layers().size() == 1 && context().allVisual()) {
                context().edit("Change Export Name", Layer.class,
                        layer -> layer.withBase(layer.base().withExportName(text)), null);
            }
        });
        row("Export name", exportName).getLabel().setTooltip(
                new Tooltip("Application-facing name for AnimationView.getElement(); leave empty for a private layer"));

        in.setId(IN_ID);
        out.setId(OUT_ID);
        for (NumberField field : new NumberField[]{in, out}) {
            field.setDecimals(3);
            field.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(field, Priority.ALWAYS);
            field.setOnAdjustEnd(() -> context().endGesture());
        }
        in.setTooltip(new Tooltip("In point: the layer shows from this time on"));
        out.setTooltip(new Tooltip("Out point: the layer shows until this time"));
        in.setOnChange((time, adjusting) -> context().edit("Change In Point", Layer.class, l -> {
            double limit = Math.max(0, l.base().outPoint() - frame());
            return l.withBase(l.base().withInPoint(Math.clamp(time, 0, limit)));
        }, adjusting ? in : null));
        out.setOnChange((time, adjusting) -> context().edit("Change Out Point", Layer.class, l -> {
            double limit = l.base().inPoint() + frame();
            return l.withBase(l.base().withOutPoint(Math.clamp(time, limit, Math.max(limit, context().animation().duration()))));
        }, adjusting ? out : null));
        HBox range = new HBox(in, out);
        range.getStyleClass().add("vec2-field");
        in.installScrubbing(row("In / Out", range).getLabel());

        visible.setId(VISIBLE_ID);
        locked.setId(LOCKED_ID);
        visible.setTooltip(new Tooltip("Visible"));
        locked.setTooltip(new Tooltip("Locked: cannot be selected on the canvas or edited"));
        for (ToggleButton button : new ToggleButton[]{visible, locked}) {
            button.getStyleClass().setAll("icon-button", "section-toggle");
            button.setFocusTraversable(false);
        }
        visible.selectedProperty().addListener((obs, was, on) -> {
            visibleIcon.setIconCode(on ? Material2MZ.VISIBILITY : Material2MZ.VISIBILITY_OFF);
            if (!updating) {
                context().edit(on ? "Show Layer" : "Hide Layer", Layer.class, l -> l.withBase(l.base().withVisible(on)), null);
            }
        });
        locked.selectedProperty().addListener((obs, was, on) -> {
            lockedIcon.setIconCode(on ? Material2AL.LOCK : Material2AL.LOCK_OPEN);
            if (!updating) {
                context().edit(on ? "Lock Layer" : "Unlock Layer", Layer.class, l -> l.withBase(l.base().withLocked(on)), null);
            }
        });
        actions().getChildren().addAll(visible, locked);
    }

    private double frame() {
        return TimeScale.frame(context().animation().frameRate());
    }

    @Override
    public boolean appliesTo(InspectorContext context) {
        return !context.layers().isEmpty();
    }

    @Override
    protected void updateFields(InspectorContext context) {
        name.setValue(context.common(Layer.class, Layer::name));
        exportName.setValue(context.layers().size() == 1 ? context.layers().getFirst().base().exportName() : "");
        in.setValue(context.common(Layer.class, l -> l.base().inPoint()));
        out.setValue(context.common(Layer.class, l -> l.base().outPoint()));
        boolean anyLocked = context.layers().stream().anyMatch(l -> l.base().locked());
        name.setDisable(anyLocked);
        exportName.setDisable(anyLocked || context.layers().size() != 1 || !context.allVisual());
        in.setDisable(anyLocked);
        out.setDisable(anyLocked);
        updating = true;
        try {
            // with a mixed selection the toggles show what a click would turn off
            visible.setSelected(context.layers().stream().allMatch(l -> l.base().visible()));
            locked.setSelected(context.layers().stream().allMatch(l -> l.base().locked()));
        } finally {
            updating = false;
        }
    }
}
