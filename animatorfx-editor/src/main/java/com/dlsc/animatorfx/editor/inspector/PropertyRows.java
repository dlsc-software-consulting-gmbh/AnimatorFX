package com.dlsc.animatorfx.editor.inspector;

import com.dlsc.animatorfx.editor.doc.PropertyUnits;
import com.dlsc.animatorfx.editor.inspector.fields.ColorField;
import com.dlsc.animatorfx.editor.inspector.fields.KeyframeToggle;
import com.dlsc.animatorfx.editor.inspector.fields.NumberField;
import com.dlsc.animatorfx.editor.inspector.fields.PaintField;
import com.dlsc.animatorfx.editor.inspector.fields.Vec2Field;
import com.dlsc.animatorfx.model.Paint;
import com.dlsc.animatorfx.model.Rgba;
import com.dlsc.animatorfx.model.Vec2;
import java.util.ArrayList;
import java.util.List;
import javafx.scene.Node;

/**
 * The inspector rows that are bound to an animatable property of the selected layers: a typed
 * editor that shows the value at the playhead (or "mixed") and writes through the context, and
 * a keyframe diamond. A row is hidden while the selected layers do not all have the property.
 */
public final class PropertyRows {

    private PropertyRows() {
    }

    /** A row for one property key. */
    public abstract static class PropertyRow {

        private final SectionBase section;
        private final String key;
        private final KeyframeToggle toggle;
        private FieldRow row;

        PropertyRow(SectionBase section, String key) {
            this.section = section;
            this.key = key;
            this.toggle = section.toggleFor(key);
        }

        final void build(String label, Node editor) {
            row = new FieldRow(label, editor, toggle);
        }

        public final String key() {
            return key;
        }

        public final FieldRow row() {
            return row;
        }

        public final KeyframeToggle toggle() {
            return toggle;
        }

        final InspectorContext context() {
            return section.context();
        }

        /** Writes a new value: the same one to every selected layer that has the property. */
        final void write(Object value, boolean adjusting) {
            context().setValue(key, current -> value, adjusting ? this : null);
        }

        final void update(InspectorContext context) {
            boolean applies = context.allHave(key);
            row.show(applies);
            if (applies) {
                toggle.setState(context.keyState(key));
                show(context.values(key));
            }
        }

        /** Shows the values of the selected layers at the playhead. */
        abstract void show(List<Object> values);
    }

    public static final class NumberRow extends PropertyRow {

        private final NumberField field;

        NumberRow(SectionBase section, String key, String label, PropertyUnits units) {
            super(section, key);
            field = new NumberField(units);
            field.setMaxWidth(Double.MAX_VALUE);
            field.setOnChange(this::write);
            field.setOnAdjustEnd(() -> context().endGesture());
            build(label, field);
            field.installScrubbing(row().getLabel());
        }

        public NumberField field() {
            return field;
        }

        @Override
        void show(List<Object> values) {
            field.setValue((Double) InspectorContext.common(values));
        }
    }

    public static final class Vec2Row extends PropertyRow {

        private final Vec2Field field;

        Vec2Row(SectionBase section, String key, String label, PropertyUnits units, String firstName,
                String secondName, boolean linkable) {
            super(section, key);
            field = new Vec2Field(units, firstName, secondName, linkable);
            field.setOnChange((edit, adjusting) -> context().setValue(key, current -> edit.apply((Vec2) current),
                    adjusting ? this : null));
            field.setOnAdjustEnd(() -> context().endGesture());
            build(label, field);
            field.installScrubbing(row().getLabel());
            if (linkable) {
                row().setAccessory(field.getLinkToggle());
            }
        }

        public Vec2Field field() {
            return field;
        }

        @Override
        void show(List<Object> values) {
            List<Double> xs = new ArrayList<>();
            List<Double> ys = new ArrayList<>();
            for (Object value : values) {
                xs.add(((Vec2) value).x());
                ys.add(((Vec2) value).y());
            }
            field.setValue(InspectorContext.common(xs), InspectorContext.common(ys));
        }
    }

    public static final class ColorRow extends PropertyRow {

        private final ColorField field = new ColorField();

        ColorRow(SectionBase section, String key, String label) {
            super(section, key);
            field.setOnChange(this::write);
            field.setOnScreenPick(color -> context().setValue(key, previous -> {
                Rgba old = (Rgba) previous;
                return new Rgba(color.r(), color.g(), color.b(), old.a());
            }, null));
            field.setOnAdjustEnd(() -> context().endGesture());
            build(label, field);
        }

        public ColorField field() {
            return field;
        }

        @Override
        void show(List<Object> values) {
            field.setValue((Rgba) InspectorContext.common(values));
        }
    }

    public static final class PaintRow extends PropertyRow {

        private final PaintField field = new PaintField();

        PaintRow(SectionBase section, String key, String label) {
            super(section, key);
            field.setOnChange(this::write);
            field.getSolidField().setOnScreenPick(color -> context().setValue(key, previous -> {
                double alpha = switch ((Paint) previous) {
                    case Paint.Solid solid -> solid.color().a();
                    case Paint.LinearGradient gradient ->
                            gradient.stops().isEmpty() ? 1 : gradient.stops().getFirst().color().a();
                    case Paint.RadialGradient gradient ->
                            gradient.stops().isEmpty() ? 1 : gradient.stops().getFirst().color().a();
                    case Paint.None none -> 1;
                };
                return new Paint.Solid(new Rgba(color.r(), color.g(), color.b(), alpha));
            }, null));
            field.setOnAdjustEnd(() -> context().endGesture());
            build(label, field);
            // the gradient editor runs under the label and diamond columns, with rows of its own
            field.setOutdent(FieldRow.LABEL_WIDTH, FieldRow.SPACING, FieldRow.DIAMOND_WIDTH + FieldRow.SPACING);
        }

        public PaintField field() {
            return field;
        }

        @Override
        void show(List<Object> values) {
            field.setValue((Paint) InspectorContext.common(values));
        }
    }
}
