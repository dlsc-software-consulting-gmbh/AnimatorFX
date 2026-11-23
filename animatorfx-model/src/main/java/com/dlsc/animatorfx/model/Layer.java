package com.dlsc.animatorfx.model;

/**
 * A layer of an animation. Parenting is expressed by nesting inside a {@link GroupLayer}.
 */
public sealed interface Layer permits ShapeLayer, TextLayer, ImageLayer, GroupLayer, SlotLayer,
        AudioLayer, VideoLayer {

    LayerBase base();

    /**
     * A copy of this layer, of the same type, with the given base.
     */
    Layer withBase(LayerBase base);

    default String id() {
        return base().id();
    }

    default String name() {
        return base().name();
    }
}
