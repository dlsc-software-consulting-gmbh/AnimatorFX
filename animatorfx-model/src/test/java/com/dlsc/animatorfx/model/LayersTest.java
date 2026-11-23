package com.dlsc.animatorfx.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fixture tree (bottom to top):
 * <pre>
 * a
 * g1
 *   b
 *   g2
 *     c
 *     slot
 *   d
 * e
 * </pre>
 */
class LayersTest {

    private static LayerBase base(String id) {
        return new LayerBase(id, id, true, false, 0, 5, Transform.IDENTITY, Animatable.of(1.0), null, List.of(), null);
    }

    private static ShapeLayer shape(String id) {
        return new ShapeLayer(base(id), new Geometry.Ellipse(Animatable.of(new Vec2(10, 10))),
                Animatable.of(new Paint.Solid(Rgba.BLACK)), null, null);
    }

    private static GroupLayer group(String id, Layer... children) {
        return new GroupLayer(base(id), List.of(children));
    }

    private final ShapeLayer a = shape("a");
    private final ShapeLayer b = shape("b");
    private final ShapeLayer c = shape("c");
    private final ShapeLayer d = shape("d");
    private final ShapeLayer e = shape("e");
    private final SlotLayer slot = new SlotLayer(base("slot"), "card", 100, 60, Transform.at(5, 5));
    private final GroupLayer g2 = group("g2", c, slot);
    private final GroupLayer g1 = group("g1", b, g2, d);
    private final Animation doc = Animation.create("Doc", 400, 300, 5).withLayers(List.of(a, g1, e));

    private static List<String> ids(List<? extends Layer> layers) {
        return layers.stream().map(Layer::id).toList();
    }

    private static GroupLayer groupOf(Animation animation, String id) {
        return (GroupLayer) Layers.find(animation, id).orElseThrow();
    }

    @Test
    void findLooksIntoNestedGroups() {
        assertSame(a, Layers.find(doc, "a").orElseThrow());
        assertSame(g2, Layers.find(doc, "g2").orElseThrow());
        assertSame(c, Layers.find(doc, "c").orElseThrow());
        assertTrue(Layers.find(doc, "nope").isEmpty());
    }

    @Test
    void flattenIsDepthFirstParentsBeforeChildren() {
        assertEquals(List.of("a", "g1", "b", "g2", "c", "slot", "d", "e"), ids(Layers.flatten(doc)));
    }

    @Test
    void parentOf() {
        assertSame(g2, Layers.parentOf(doc, "c").orElseThrow());
        assertSame(g1, Layers.parentOf(doc, "g2").orElseThrow());
        assertTrue(Layers.parentOf(doc, "a").isEmpty(), "top-level layer");
        assertTrue(Layers.parentOf(doc, "nope").isEmpty());
    }

    @Test
    void slots() {
        assertEquals(List.of(slot), Layers.slots(doc));
    }

    @Test
    void replaceNestedLayerSharesUntouchedSubtrees() {
        Animation result = Layers.replace(doc, "c", l -> l.withBase(l.base().withName("renamed")));

        assertEquals("renamed", Layers.find(result, "c").orElseThrow().name());
        assertEquals(ids(Layers.flatten(doc)), ids(Layers.flatten(result)));
        assertSame(a, result.layers().get(0));
        assertSame(e, result.layers().get(2));
        GroupLayer newG1 = groupOf(result, "g1");
        assertNotSame(g1, newG1);
        assertSame(g1.base(), newG1.base());
        assertSame(b, newG1.children().get(0));
        assertSame(d, newG1.children().get(2));
        assertSame(slot, groupOf(result, "g2").children().get(1));
        assertEquals("c", Layers.find(doc, "c").orElseThrow().name(), "original is unchanged");
    }

    @Test
    void replaceTopLevelLayer() {
        Animation result = Layers.replace(doc, "a", l -> e.withBase(e.base().withName("swapped")));
        assertEquals("swapped", result.layers().get(0).name());
        assertSame(g1, result.layers().get(1));
        assertSame(e, result.layers().get(2));
    }

    @Test
    void replaceUnknownIdOrUnchangedLayerReturnsSameAnimation() {
        assertSame(doc, Layers.replace(doc, "nope", l -> shape("x")));
        assertSame(doc, Layers.replace(doc, "c", l -> l));
    }

    @Test
    void removeNestedLayerSharesUntouchedSubtrees() {
        Animation result = Layers.remove(doc, "c");

        assertEquals(List.of("a", "g1", "b", "g2", "slot", "d", "e"), ids(Layers.flatten(result)));
        assertSame(a, result.layers().get(0));
        assertSame(e, result.layers().get(2));
        assertSame(b, groupOf(result, "g1").children().get(0));
        assertSame(d, groupOf(result, "g1").children().get(2));
        assertSame(slot, groupOf(result, "g2").children().get(0));
    }

    @Test
    void removeGroupRemovesItsChildren() {
        Animation result = Layers.remove(doc, "g1");
        assertEquals(List.of("a", "e"), ids(Layers.flatten(result)));
        assertSame(doc, Layers.remove(doc, "nope"));
    }

    @Test
    void insertAtTopLevelAndIntoGroup() {
        ShapeLayer x = shape("x");

        Animation top = Layers.insert(doc, null, 1, x);
        assertEquals(List.of("a", "x", "g1", "e"), ids(top.layers()));
        assertSame(g1, top.layers().get(2));

        Animation end = Layers.insert(doc, null, 3, x);
        assertEquals(List.of("a", "g1", "e", "x"), ids(end.layers()));

        Animation nested = Layers.insert(doc, "g2", 1, x);
        assertEquals(List.of("c", "x", "slot"), ids(groupOf(nested, "g2").children()));
        assertSame(x, groupOf(nested, "g2").children().get(1));
        assertSame(a, nested.layers().get(0));
        assertSame(b, groupOf(nested, "g1").children().get(0));
        assertSame(c, groupOf(nested, "g2").children().get(0));
    }

    @Test
    void insertRejectsBadTargets() {
        ShapeLayer x = shape("x");
        assertThrows(IllegalArgumentException.class, () -> Layers.insert(doc, "nope", 0, x));
        assertThrows(IllegalArgumentException.class, () -> Layers.insert(doc, "a", 0, x), "parent is not a group");
        assertThrows(IllegalArgumentException.class, () -> Layers.insert(doc, null, 0, shape("c")), "duplicate id");
        assertThrows(IllegalArgumentException.class,
                () -> Layers.insert(doc, null, 0, group("x", shape("y"), shape("d"))), "duplicate descendant id");
        assertThrows(IndexOutOfBoundsException.class, () -> Layers.insert(doc, null, 4, x));
        assertThrows(IndexOutOfBoundsException.class, () -> Layers.insert(doc, "g2", -1, x));
    }

    @Test
    void moveBetweenParents() {
        Animation result = Layers.move(doc, "c", null, 0);
        assertEquals(List.of("c", "a", "g1", "b", "g2", "slot", "d", "e"), ids(Layers.flatten(result)));
        assertSame(c, result.layers().get(0));
        assertSame(a, result.layers().get(1));
        assertSame(b, groupOf(result, "g1").children().get(0));
        assertSame(slot, groupOf(result, "g2").children().get(0));

        Animation into = Layers.move(doc, "e", "g2", 2);
        assertEquals(List.of("a", "g1", "b", "g2", "c", "slot", "e", "d"), ids(Layers.flatten(into)));
    }

    @Test
    void moveIndexAppliesAfterRemoval() {
        assertEquals(List.of("g1", "e", "a"), ids(Layers.move(doc, "a", null, 2).layers()));
        assertEquals(List.of("g1", "a", "e"), ids(Layers.move(doc, "a", null, 1).layers()));
        assertEquals(List.of("d", "b", "g2"), ids(groupOf(Layers.move(doc, "d", "g1", 0), "g1").children()));
        assertThrows(IndexOutOfBoundsException.class, () -> Layers.move(doc, "a", null, 3));
    }

    @Test
    void moveGroupKeepsItsSubtree() {
        Animation result = Layers.move(doc, "g2", null, 3);
        assertEquals(List.of("a", "g1", "b", "d", "e", "g2", "c", "slot"), ids(Layers.flatten(result)));
        assertSame(g2, result.layers().get(3));
    }

    @Test
    void moveRejectsBadTargets() {
        assertThrows(IllegalArgumentException.class, () -> Layers.move(doc, "g1", "g1", 0), "into itself");
        assertThrows(IllegalArgumentException.class, () -> Layers.move(doc, "g1", "g2", 0), "into a descendant");
        assertThrows(IllegalArgumentException.class, () -> Layers.move(doc, "a", "nope", 0), "unknown parent");
        assertThrows(IllegalArgumentException.class, () -> Layers.move(doc, "a", "e", 0), "parent is not a group");
        assertThrows(IllegalArgumentException.class, () -> Layers.move(doc, "nope", null, 0), "unknown layer");
    }

    @Test
    void animationFactoryAndMarkers() {
        Animation created = Animation.create("New", 640, 480, 3);
        assertEquals(Animation.CURRENT_FORMAT, created.formatVersion());
        assertEquals(60, created.frameRate());
        assertEquals(Rgba.TRANSPARENT, created.background());
        assertTrue(created.layers().isEmpty());

        Animation marked = created.withMarkers(List.of(new Marker("intro", 0, 1), new Marker("loop", 1, 3)));
        assertEquals(new Marker("loop", 1, 3), marked.marker("loop").orElseThrow());
        assertFalse(marked.marker("outro").isPresent());
        assertThrows(UnsupportedOperationException.class, () -> marked.markers().add(new Marker("x", 0, 0)));
    }

    @Test
    void layerBaseFactory() {
        LayerBase created = LayerBase.create("Layer", 4);
        assertEquals("Layer", created.name());
        assertTrue(created.visible());
        assertFalse(created.locked());
        assertEquals(0, created.inPoint());
        assertEquals(4, created.outPoint());
        assertSame(Transform.IDENTITY, created.transform());
        assertEquals(1.0, created.opacity().at(0));
        assertTrue(created.effects().isEmpty());
        assertFalse(created.id().equals(LayerBase.create("Layer", 4).id()));
    }

    @Test
    void audioLayersAreLeafLayers() {
        AudioLayer audio = AudioLayer.create("Music", "snd", 1, 4);
        Animation withAudio = Layers.insert(doc, "g2", 1, audio);

        assertEquals(List.of("a", "g1", "b", "g2", "c", audio.id(), "slot", "d", "e"), ids(Layers.flatten(withAudio)));
        assertSame(audio, Layers.find(withAudio, audio.id()).orElseThrow());
        assertEquals("g2", Layers.parentOf(withAudio, audio.id()).orElseThrow().id());
        assertEquals(List.of(slot), Layers.slots(withAudio));

        Animation muted = Layers.replace(withAudio, audio.id(), l -> ((AudioLayer) l).withMuted(true));
        assertEquals(audio.withMuted(true), Layers.find(muted, audio.id()).orElseThrow());
        assertSame(a, muted.layers().get(0));

        Animation moved = Layers.move(withAudio, audio.id(), null, 0);
        assertEquals(List.of(audio.id(), "a", "g1", "e"), ids(moved.layers()));
        assertThrows(IllegalArgumentException.class, () -> Layers.insert(withAudio, audio.id(), 0, shape("x")),
                "an audio layer is not a group");
        assertEquals(doc, Layers.remove(withAudio, audio.id()));
    }

    @Test
    void audioLayerAndCueFactories() {
        AudioLayer audio = AudioLayer.create("Music", "snd", 1, 4);
        assertEquals(new AudioLayer(audio.base(), "snd", 0, Animatable.of(1.0), Animatable.of(0.0), false, false),
                audio);
        assertEquals("Music", audio.name());
        assertEquals(1, audio.base().inPoint());
        assertEquals(4, audio.base().outPoint());
        assertTrue(audio.base().visible());
        assertFalse(audio.id().equals(AudioLayer.create("Music", "snd", 1, 4).id()));
        assertEquals(new AudioLayer(audio.base(), "other", 2, Animatable.of(0.5), Animatable.of(1.0), true, true),
                audio.withAssetId("other").withClipOffset(2).withVolume(Animatable.of(0.5))
                        .withBalance(Animatable.of(1.0)).withLoop(true).withMuted(true));

        SoundCue cue = SoundCue.create("Tick", 1.5, "snd");
        assertEquals(new SoundCue(cue.id(), "Tick", 1.5, "snd", 1, false), cue);
        assertFalse(cue.id().equals(SoundCue.create("Tick", 1.5, "snd").id()));
        assertEquals(new SoundCue("id", "Tock", 2, "other", 0.5, true),
                cue.withId("id").withName("Tock").withTime(2).withAssetId("other").withVolume(0.5).withMuted(true));
    }

    @Test
    void animationCues() {
        Animation created = Animation.create("New", 640, 480, 3);
        assertEquals(Animation.CURRENT_FORMAT, created.formatVersion());
        assertTrue(created.cues().isEmpty());
        assertTrue(new Animation(2, "New", 640, 480, 60, 3, Rgba.TRANSPARENT, List.of(), List.of(), List.of())
                .cues().isEmpty());

        SoundCue cue = SoundCue.create("Tick", 1, "snd");
        Animation withCue = created.withCues(List.of(cue));
        assertEquals(List.of(cue), withCue.cues());
        assertFalse(created.equals(withCue));
        assertThrows(UnsupportedOperationException.class, () -> withCue.cues().add(cue));
        assertEquals(List.of(cue), withCue.withName("x").withWidth(1).withHeight(1).withFrameRate(1).withDuration(1)
                .withBackground(Rgba.BLACK).withMarkers(List.of()).withAssets(List.of()).withLayers(List.of())
                .withFormatVersion(1).cues());
    }
}
