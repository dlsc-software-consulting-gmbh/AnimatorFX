package com.dlsc.animatorfx.player;

import com.dlsc.animatorfx.model.Effect;
import com.dlsc.animatorfx.model.Paint;
import com.dlsc.animatorfx.model.PathData;
import com.dlsc.animatorfx.model.Rgba;
import com.dlsc.animatorfx.model.Vec2;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The resolved values of one layer at one time. All values are local to the layer: a group's
 * transform and opacity are not baked into its children.
 * <p>
 * Arrays are shared between frames and must not be modified. Values that did not change keep
 * their instance from frame to frame where possible, so an identity check is a cheap first test
 * for "unchanged".
 *
 * @param visible     whether the layer is switched on and the time is inside its in/out range
 * @param matrix      the layer transform as {@code {mxx, mxy, tx, myx, myy, ty}}, see
 *                    {@link com.dlsc.animatorfx.model.Transform#matrixAt}; a motion path is already applied
 * @param opacity     {@code 0..1}
 * @param fillPath    the untrimmed outline of a shape layer; {@code null} for other layers
 * @param strokePath  the outline a shape layer's stroke follows: the trimmed outline, or the very
 *                    instance of {@code fillPath} when nothing is trimmed away; {@code null} for other layers
 * @param fill        the fill of a shape or text layer; {@code null} for other layers
 * @param strokePaint the stroke paint of a shape or text layer; {@code null} without a stroke
 * @param strokeWidth 0 without a stroke
 * @param dashOffset  0 without a stroke
 * @param fontSize    the font size of a text layer; 0 for other layers
 * @param effects     the layer's effects in list order
 * @param position    where the anchor lands in the parent: the point on the motion path if there
 *                    is one, otherwise {@code transform.position}
 * @param scale       {@code transform.scale}
 * @param rotation    {@code transform.rotation} in degrees, plus the motion path's tangent angle
 *                    when the layer is oriented to its path
 * @param volume      the volume of an audio or video layer, as stored in the document ({@code 0..1}); 1
 *                    for other layers
 * @param balance     the balance of an audio layer, {@code -1} (left) to {@code 1} (right); 0 for
 *                    other layers
 */
public record LayerState(boolean visible, double[] matrix, double opacity, PathData fillPath, PathData strokePath,
                         Paint fill, Paint strokePaint, double strokeWidth, double dashOffset, double fontSize,
                         List<EffectState> effects, Vec2 position, Vec2 scale, double rotation,
                         double volume, double balance) {

    /**
     * The resolved values of one effect.
     *
     * @param source the effect of the document this state was sampled from
     * @param values {@code {radius}} for a blur, {@code {radius, offsetX, offsetY, spread}} for a
     *               drop shadow, {@code {level}} for a glow
     * @param color  the colour of a drop shadow; {@code null} for other effects
     */
    public record EffectState(Effect source, double[] values, Rgba color) {

        public EffectState {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(values, "values");
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof EffectState other && source.equals(other.source)
                    && Arrays.equals(values, other.values) && Objects.equals(color, other.color);
        }

        @Override
        public int hashCode() {
            return Objects.hash(source, Arrays.hashCode(values), color);
        }
    }

    public LayerState {
        Objects.requireNonNull(matrix, "matrix");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(scale, "scale");
        effects = List.copyOf(effects);
    }

    /**
     * The state of a layer that is not an audio layer: volume 1, balance 0.
     */
    public LayerState(boolean visible, double[] matrix, double opacity, PathData fillPath, PathData strokePath,
                      Paint fill, Paint strokePaint, double strokeWidth, double dashOffset, double fontSize,
                      List<EffectState> effects, Vec2 position, Vec2 scale, double rotation) {
        this(visible, matrix, opacity, fillPath, strokePath, fill, strokePaint, strokeWidth, dashOffset, fontSize,
                effects, position, scale, rotation, 1, 0);
    }

    LayerState withVisible(boolean visible) {
        return new LayerState(visible, matrix, opacity, fillPath, strokePath, fill, strokePaint, strokeWidth,
                dashOffset, fontSize, effects, position, scale, rotation, volume, balance);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof LayerState other && visible == other.visible && Arrays.equals(matrix, other.matrix)
                && Double.compare(opacity, other.opacity) == 0 && Objects.equals(fillPath, other.fillPath)
                && Objects.equals(strokePath, other.strokePath) && Objects.equals(fill, other.fill)
                && Objects.equals(strokePaint, other.strokePaint)
                && Double.compare(strokeWidth, other.strokeWidth) == 0
                && Double.compare(dashOffset, other.dashOffset) == 0
                && Double.compare(fontSize, other.fontSize) == 0 && effects.equals(other.effects)
                && position.equals(other.position) && scale.equals(other.scale)
                && Double.compare(rotation, other.rotation) == 0
                && Double.compare(volume, other.volume) == 0 && Double.compare(balance, other.balance) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(visible, Arrays.hashCode(matrix), opacity, fillPath, strokePath, fill, strokePaint,
                strokeWidth, dashOffset, fontSize, effects, position, scale, rotation, volume, balance);
    }
}
