package com.dlsc.animatorfx.editor.timeline;

import com.dlsc.animatorfx.editor.audio.Waveform;
import com.dlsc.animatorfx.editor.doc.EditorDocument;
import com.dlsc.animatorfx.editor.doc.Selection.KeyframeRef;
import com.dlsc.animatorfx.editor.timeline.TimelineRow.LayerRow;
import com.dlsc.animatorfx.editor.timeline.TimelineRow.PropertyRow;
import com.dlsc.animatorfx.editor.ui.Palette;
import com.dlsc.animatorfx.editor.ui.Palette.Tone;
import com.dlsc.animatorfx.editor.ui.TiledCanvas;
import com.dlsc.animatorfx.model.Animation;
import com.dlsc.animatorfx.model.AudioLayer;
import com.dlsc.animatorfx.model.VideoLayer;
import com.dlsc.animatorfx.model.Easing;
import com.dlsc.animatorfx.model.Keyframe;
import com.dlsc.animatorfx.model.Layer;
import com.dlsc.animatorfx.model.SlotLayer;
import java.util.List;
import java.util.Set;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.ContextMenu;
import javafx.scene.layout.Region;

/**
 * The tracks of the timeline, drawn on a canvas with the colours of the stylesheet. A layer row
 * shows the bar of its in/out range and, while collapsed, small dots at the times of its
 * keyframes; the bar of an audio layer also shows the waveform of its sound; a property row shows its keyframes as diamonds (squares for hold keyframes) on a
 * thin connector line. The time past the duration is shaded.
 *
 * <p>Pointer handling is in {@link TrackInteraction}: selecting, moving and adding keyframes,
 * the marquee, and trimming and moving the in/out bars.
 */
public final class TrackPane extends Region {

    /** The part of an in/out bar under the pointer. */
    public enum BarPart {START, BODY, END}

    /** What is at a position of the tracks. */
    public sealed interface Hit {

        /** A keyframe of a property row. */
        record Key(int row, KeyframeRef ref, double time) implements Hit {
        }

        /** The in/out bar of a layer row. */
        record Bar(int row, String layerId, BarPart part) implements Hit {
        }

        /** The editable volume graph; {@code key} is null between its points. */
        record Volume(int row, String layerId, Keyframe<Double> key) implements Hit {
        }

        /** Free space: {@code row} is the index of the row, or -1 below the last one. */
        record Empty(int row) implements Hit {
        }
    }

    static final double KEY_SIZE = 4.5;
    private static final double KEY_REACH = 6;
    private static final double BAR_INSET = 6;
    private static final double BAR_GRIP = 4;
    private static final double AUDIO_BAR_INSET = 2;
    private static final double HATCH = 5;

    private final TimelineView timeline;
    private final TiledCanvas canvas = new TiledCanvas();
    private final TrackInteraction interaction;
    private String volumeEditingLayer;

    TrackPane(TimelineView timeline) {
        this.timeline = timeline;
        getStyleClass().add("timeline-tracks");
        setFocusTraversable(true);
        getChildren().add(canvas);
        interaction = new TrackInteraction(timeline, this);
    }

    /** The menu for the selected keyframes: Easing ▸ Linear / Hold / presets, and Delete. */
    public ContextMenu getKeyframeMenu() {
        return interaction.getKeyframeMenu();
    }

    /** The audio track menu, including its Edit Volume toggle. */
    public ContextMenu getAudioMenu(String layerId) {
        return interaction.getAudioMenu(layerId);
    }

    void editVolume(String layerId) {
        interaction.cancel();
        volumeEditingLayer = layerId;
        timeline.requestLayout();
    }

    boolean isEditingVolume(String layerId) {
        return layerId.equals(volumeEditingLayer);
    }

    double volumeToY(int row, double value) {
        return timeline.rowTop(row) + AUDIO_BAR_INSET + 1.5
                + (1 - Math.clamp(value, 0, 1)) * (TimelineView.ROW_HEIGHT - 2 * AUDIO_BAR_INSET - 4);
    }

    double volumeAtY(int row, double y) {
        double top = volumeToY(row, 1);
        return Math.clamp(1 - (y - top) / (TimelineView.ROW_HEIGHT - 2 * AUDIO_BAR_INSET - 4), 0, 1);
    }

    void cancelGesture() {
        interaction.cancel();
    }

    /** The x coordinate of a time. */
    public double timeToX(double time) {
        return timeline.getScale().toX(time);
    }

    /** The y coordinate of the middle of a row; the row may be out of view. */
    public double rowCenter(int row) {
        return timeline.rowTop(row) + TimelineView.ROW_HEIGHT / 2;
    }

    /** The index of the row of a property; -1 when it is not listed. */
    public int rowOf(String layerId, String propertyKey) {
        List<TimelineRow> rows = timeline.getRows();
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i) instanceof PropertyRow p && p.layerId().equals(layerId) && p.key().equals(propertyKey)) {
                return i;
            }
        }
        return -1;
    }

    /** The index of the row of a layer; -1 when it is inside a collapsed group. */
    public int rowOf(String layerId) {
        List<TimelineRow> rows = timeline.getRows();
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i) instanceof LayerRow && rows.get(i).layerId().equals(layerId)) {
                return i;
            }
        }
        return -1;
    }

    /** What is under a position: the nearest keyframe within reach, a part of a bar, or free space. */
    public Hit hitAt(double x, double y) {
        EditorDocument doc = timeline.getDocument();
        int index = timeline.rowAt(y);
        if (doc == null || index < 0) {
            return new Hit.Empty(-1);
        }
        TimeScale scale = timeline.getScale();
        TimelineRow row = timeline.getRows().get(index);
        if (row instanceof PropertyRow property) {
            Keyframe<?> nearest = null;
            double distance = KEY_REACH;
            for (Keyframe<?> keyframe : property.property().value().keyframes()) {
                double d = Math.abs(scale.toX(keyframe.time()) - x);
                if (d <= distance) {
                    distance = d;
                    nearest = keyframe;
                }
            }
            if (nearest != null) {
                return new Hit.Key(index, new KeyframeRef(row.layerId(), property.key(), nearest.id()), nearest.time());
            }
            return new Hit.Empty(index);
        }
        double duration = doc.getAnimation().duration();
        double start = scale.toX(barStart(row.layer(), duration));
        double end = scale.toX(barEnd(row.layer(), duration));
        if (x < start - BAR_GRIP || x > end + BAR_GRIP) {
            return new Hit.Empty(index);
        }
        if (row.layer() instanceof AudioLayer audio && isEditingVolume(audio.id()) && x >= start && x <= end) {
            Keyframe<Double> nearest = null;
            double distance = KEY_REACH;
            for (Keyframe<Double> key : audio.volume().keyframes()) {
                if (key.time() < barStart(audio, duration) || key.time() > barEnd(audio, duration)) continue;
                double d = Math.hypot(scale.toX(key.time()) - x, volumeToY(index, key.value()) - y);
                if (d <= distance) {
                    nearest = key;
                    distance = d;
                }
            }
            return new Hit.Volume(index, audio.id(), nearest);
        }
        boolean narrow = end - start < 3 * BAR_GRIP;
        BarPart part = x <= start + (narrow ? 0 : BAR_GRIP) ? BarPart.START
                : x >= end - (narrow ? 0 : BAR_GRIP) ? BarPart.END : BarPart.BODY;
        return new Hit.Bar(index, row.layerId(), part);
    }

    private static double barStart(Layer layer, double duration) {
        return Math.clamp(layer.base().inPoint(), 0, Math.max(0, duration));
    }

    private static double barEnd(Layer layer, double duration) {
        return Math.clamp(layer.base().outPoint(), barStart(layer, duration), Math.max(barStart(layer, duration), duration));
    }

    // ------------------------------------------------------------------ drawing

    void draw() {
        double width = getWidth();
        double height = getHeight();
        canvas.draw(width, height, g -> draw(g, width, height));
    }

    private void draw(GraphicsContext g, double width, double height) {
        EditorDocument doc = timeline.getDocument();
        if (doc == null || width <= 0 || height <= 0) {
            return;
        }
        Palette palette = timeline.getPalette();
        Animation animation = doc.getAnimation();
        TimeScale scale = timeline.getScale();
        List<TimelineRow> rows = timeline.getRows();
        Set<KeyframeRef> selectedKeys = doc.getSelection().keyframes();
        int first = Math.max(0, (int) Math.floor(timeline.getScrollY() / TimelineView.ROW_HEIGHT));
        g.setLineWidth(1);

        for (int i = first; i < rows.size() && timeline.rowTop(i) < height; i++) {
            TimelineRow row = rows.get(i);
            double y = timeline.rowTop(i);
            if (row instanceof PropertyRow) {
                g.setFill(palette.get(Tone.ROW_ALT));
                g.fillRect(0, y, width, TimelineView.ROW_HEIGHT);
            } else if (timeline.isHighlighted(row.layerId())) {
                g.setFill(palette.get(Tone.ACCENT_SOFT));
                g.fillRect(0, y, width, TimelineView.ROW_HEIGHT);
            }
        }

        RulerTicks ticks = RulerTicks.choose(scale.pixelsPerSecond(), animation.frameRate());
        g.setStroke(palette.get(Tone.GRID));
        for (long i = Math.max(0, (long) Math.floor(scale.toTime(0) / ticks.major())); ; i++) {
            double x = Math.round(scale.toX(i * ticks.major())) + 0.5;
            if (x > width) {
                break;
            }
            g.strokeLine(x, 0, x, height);
        }

        g.setStroke(palette.get(Tone.LINE));
        for (int i = first; i < rows.size() && timeline.rowTop(i) < height; i++) {
            double y = timeline.rowTop(i) + TimelineView.ROW_HEIGHT - 0.5;
            g.strokeLine(0, y, width, y);
        }

        for (int i = first; i < rows.size() && timeline.rowTop(i) < height; i++) {
            TimelineRow row = rows.get(i);
            double y = timeline.rowTop(i);
            if (row instanceof LayerRow layerRow) {
                drawBar(g, palette, scale, layerRow, y, animation.duration());
            } else {
                drawKeyframes(g, palette, scale, (PropertyRow) row, y, selectedKeys);
            }
        }

        double end = scale.toX(animation.duration());
        if (end < width) {
            g.setFill(palette.get(Tone.BG, 0.55));
            g.fillRect(Math.max(0, end), 0, width - Math.max(0, end), height);
            g.setStroke(palette.get(Tone.LINE));
            g.strokeLine(Math.round(end) + 0.5, 0, Math.round(end) + 0.5, height);
        }

        double[] marquee = interaction.marquee();
        if (marquee != null) {
            double x = Math.round(marquee[0]) + 0.5;
            double y = Math.round(marquee[1] - timeline.getScrollY()) + 0.5;
            g.setFill(palette.get(Tone.ACCENT_SOFT));
            g.fillRect(x, y, marquee[2], marquee[3]);
            g.setStroke(palette.get(Tone.ACCENT));
            g.strokeRect(x, y, Math.round(marquee[2]), Math.round(marquee[3]));
        }
    }

    private void drawBar(GraphicsContext g, Palette palette, TimeScale scale, LayerRow row, double y, double duration) {
        Layer layer = row.layer();
        if (layer instanceof AudioLayer audio) {
            drawAudioBar(g, palette, scale, row, audio, y, duration);
            return;
        }
        if (layer instanceof VideoLayer video) {
            drawVideoBar(g, palette, scale, video, y, duration);
            return;
        }
        boolean slot = layer instanceof SlotLayer;
        double x1 = Math.round(scale.toX(barStart(layer, duration)));
        double x2 = Math.max(x1 + 2, Math.round(scale.toX(barEnd(layer, duration))));
        double top = y + BAR_INSET;
        double barHeight = TimelineView.ROW_HEIGHT - 2 * BAR_INSET - 1;
        g.setGlobalAlpha(layer.base().visible() ? 1 : 0.4);
        g.setFill(palette.get(slot ? Tone.KEY_BAR : Tone.ACCENT_BAR));
        g.fillRoundRect(x1, top, x2 - x1, barHeight, 3, 3);
        g.setStroke(palette.get(slot ? Tone.KEY : Tone.ACCENT));
        g.strokeRoundRect(x1 + 0.5, top + 0.5, x2 - x1 - 1, barHeight - 1, 3, 3);
        if (!row.expanded()) {
            // a collapsed layer still tells where its keyframes are
            double cy = top + barHeight / 2;
            g.setFill(palette.get(slot ? Tone.FG : Tone.KEY));
            for (double time : TimelineRows.keyTimes(layer)) {
                double x = Math.round(scale.toX(time)) + 0.5;
                diamond(g, x, cy, 3);
                g.fill();
            }
        }
        g.setGlobalAlpha(1);
    }

    private void drawVideoBar(GraphicsContext g, Palette palette, TimeScale scale, VideoLayer layer,
                              double y, double duration) {
        double x1 = Math.round(scale.toX(barStart(layer, duration)));
        double x2 = Math.max(x1 + 2, Math.round(scale.toX(barEnd(layer, duration))));
        double top = y + AUDIO_BAR_INSET;
        double height = TimelineView.ROW_HEIGHT - 2 * AUDIO_BAR_INSET - 1;
        g.setGlobalAlpha(layer.base().visible() ? 1 : 0.4);
        g.setFill(palette.get(Tone.KEY_BAR));
        g.fillRoundRect(x1, top, x2 - x1, height, 3, 3);
        g.setStroke(palette.get(Tone.KEY));
        g.strokeRoundRect(x1 + 0.5, top + 0.5, x2 - x1 - 1, height - 1, 3, 3);
        g.save();
        g.beginPath();
        g.rect(x1 + 1, top + 1, Math.max(0, x2 - x1 - 2), height - 2);
        g.clip();
        double clip = timeline.clipDurationOf(layer.assetId());
        AudioClips.Layout layout = AudioClips.layout(layer.base().inPoint(), layer.base().outPoint(),
                layer.clipOffset(), clip, layer.loop());
        double end = Math.clamp(scale.toX(layout.soundEnd()), x1, x2);
        if (end < x2) {
            g.setFill(palette.get(Tone.PANEL, 0.7));
            g.fillRect(end, top, x2 - end, height);
        }
        g.setFill(palette.get(Tone.KEY, 0.55));
        for (double x = Math.max(0, x1) + 4; x < Math.min(getWidth(), x2) - 2; x += 12) {
            g.fillRect(x, top + 2, 3, 2);
            g.fillRect(x, top + height - 4, 3, 2);
        }
        g.setStroke(palette.get(Tone.KEY));
        for (double boundary : layout.loopBoundaries()) {
            double x = scale.toX(boundary);
            if (x >= 0 && x <= getWidth()) g.strokeLine(x, top, x, top + height);
        }
        g.restore();
        g.setGlobalAlpha(1);
    }

    /**
     * The bar of an audio layer: taller than the others, in the colour of sound, with the
     * waveform inside it once that is known. The sound at the in-point is the clip offset; a
     * looping sound repeats with a line where it starts over; the part of the bar after the end
     * of a sound that does not loop is hatched. A muted or hidden layer has a faint waveform. The
     * volume, when it is animated, runs over the waveform as a thin line.
     */
    private void drawAudioBar(GraphicsContext g, Palette palette, TimeScale scale, LayerRow row, AudioLayer layer,
                              double y, double duration) {
        double width = getWidth();
        double x1 = Math.round(scale.toX(barStart(layer, duration)));
        double x2 = Math.max(x1 + 2, Math.round(scale.toX(barEnd(layer, duration))));
        double top = y + AUDIO_BAR_INSET;
        double barHeight = TimelineView.ROW_HEIGHT - 2 * AUDIO_BAR_INSET - 1;
        boolean audible = layer.base().visible() && !layer.muted();
        g.setGlobalAlpha(layer.base().visible() ? 1 : 0.5);
        g.setFill(palette.get(Tone.AUDIO_BAR));
        g.fillRoundRect(x1, top, x2 - x1, barHeight, 3, 3);

        // where the sound lies follows from its length, which is known before its waveform is
        Waveform waveform = timeline.waveformOf(layer.assetId()).orElse(null);
        double clip = timeline.clipDurationOf(layer.assetId());
        double in = layer.base().inPoint();
        AudioClips.Layout layout = AudioClips.layout(in, Math.max(in, layer.base().outPoint()), layer.clipOffset(),
                clip, layer.loop());
        double soundEnd = Math.clamp(Math.round(scale.toX(layout.soundEnd())), x1, x2);
        g.save();
        g.beginPath();
        g.rect(x1 + 1, top + 1, x2 - x1 - 2, barHeight - 2);
        g.clip();
        if (soundEnd < x2 - 1) {
            // no sound from here on
            g.setFill(palette.get(Tone.PANEL, 0.6));
            g.fillRect(soundEnd, top, x2 - soundEnd, barHeight);
            g.setStroke(palette.get(Tone.AUDIO, 0.3));
            for (double hx = soundEnd - barHeight + (HATCH - (soundEnd % HATCH)); hx < x2; hx += HATCH) {
                if (hx + barHeight >= 0 && hx <= width) {
                    g.strokeLine(hx, top + barHeight, hx + barHeight, top);
                }
            }
            g.setStroke(palette.get(Tone.AUDIO, 0.7));
            g.strokeLine(soundEnd + 0.5, top, soundEnd + 0.5, top + barHeight);
        }
        if (waveform != null) {
            WaveformPainter.paint(g, waveform, scale, in, layer.clipOffset(), layer.loop(), x1, x2, top + 1.5,
                    barHeight - 3, width, palette.get(Tone.WAVE, audible ? 1 : 0.35));
        }
        if (layer.loop() && clip > 0 && scale.toDuration(1) * 6 < clip) {
            g.setStroke(palette.get(Tone.FG, 0.55));
            g.setLineDashes(2, 2);
            for (double time : layout.loopBoundaries()) {
                double x = Math.round(scale.toX(time)) + 0.5;
                if (x >= 0 && x <= width) {
                    g.strokeLine(x, top, x, top + barHeight);
                }
            }
            g.setLineDashes();
        }
        boolean editing = isEditingVolume(layer.id());
        double envelopeEnd = editing ? x2 : soundEnd;
        if ((layer.volume().isAnimated() || editing) && envelopeEnd > x1 + 1) {
            // the volume envelope, where there is sound: full volume at the top of the bar
            g.save();
            g.beginPath();
            g.rect(x1, top, envelopeEnd - x1, barHeight);
            g.clip();
            g.setStroke(palette.get(Tone.KEY, audible ? 0.95 : 0.45));
            g.beginPath();
            double room = barHeight - (editing ? 4 : 3);
            int from = (int) Math.max(x1, 0);
            int to = (int) Math.min(envelopeEnd + 2, width);
            for (int x = from; x <= to; x += 2) {
                double volume = Math.clamp(layer.volume().at(scale.toTime(x)), 0, 1);
                double vy = top + 1.5 + (1 - volume) * room;
                if (x == from) {
                    g.moveTo(x, vy);
                } else {
                    g.lineTo(x, vy);
                }
            }
            g.stroke();
            g.restore();
        }
        g.restore();

        g.setStroke(palette.get(audible ? Tone.AUDIO : Tone.MUTED));
        g.strokeRoundRect(x1 + 0.5, top + 0.5, x2 - x1 - 1, barHeight - 1, 3, 3);
        if (editing) {
            int index = rowOf(layer.id());
            for (Keyframe<Double> key : layer.volume().keyframes()) {
                double x = scale.toX(key.time());
                if (x < x1 || x > x2 || x < -KEY_REACH || x > width + KEY_REACH) continue;
                double vy = volumeToY(index, key.value());
                boolean selected = timeline.getDocument().getSelection().keyframes()
                        .contains(new KeyframeRef(layer.id(), "volume", key.id()));
                g.setFill(palette.get(selected ? Tone.FG : Tone.KEY));
                g.fillOval(x - 3, vy - 3, 6, 6);
                g.setStroke(palette.get(Tone.AUDIO));
                g.strokeOval(x - 3, vy - 3, 6, 6);
            }
        } else if (!row.expanded()) {
            double cy = top + barHeight / 2;
            g.setFill(palette.get(Tone.KEY));
            for (double time : TimelineRows.keyTimes(layer)) {
                diamond(g, Math.round(scale.toX(time)) + 0.5, cy, 3);
                g.fill();
            }
        }
        g.setGlobalAlpha(1);
    }

    private void drawKeyframes(GraphicsContext g, Palette palette, TimeScale scale, PropertyRow row, double y,
                               Set<KeyframeRef> selected) {
        List<? extends Keyframe<?>> keyframes = row.property().value().keyframes();
        if (keyframes.isEmpty()) {
            return;
        }
        double cy = Math.round(y + (TimelineView.ROW_HEIGHT - 1) / 2) + 0.5;
        if (keyframes.size() > 1) {
            g.setStroke(palette.get(Tone.KEY, 0.45));
            g.strokeLine(scale.toX(keyframes.get(0).time()), cy, scale.toX(keyframes.get(keyframes.size() - 1).time()), cy);
        }
        for (Keyframe<?> keyframe : keyframes) {
            double x = Math.round(scale.toX(keyframe.time())) + 0.5;
            boolean isSelected = selected.contains(new KeyframeRef(row.layerId(), row.key(), keyframe.id()));
            // selected keyframes are a little larger
            double grow = isSelected ? 1 : 0;
            if (keyframe.easing() instanceof Easing.Hold) {
                g.beginPath();
                g.rect(x - 3.5 - grow, cy - 3.5 - grow, 7 + 2 * grow, 7 + 2 * grow);
                g.closePath();
            } else {
                diamond(g, x, cy, KEY_SIZE + grow);
            }
            if (isSelected) {
                g.setFill(palette.get(Tone.ON_ACCENT));
                g.fill();
                g.setStroke(palette.get(Tone.ACCENT));
                g.setLineWidth(1.5);
                g.stroke();
                g.setLineWidth(1);
            } else {
                g.setFill(palette.get(Tone.KEY));
                g.fill();
            }
        }
    }

    private static void diamond(GraphicsContext g, double x, double y, double size) {
        g.beginPath();
        g.moveTo(x, y - size);
        g.lineTo(x + size, y);
        g.lineTo(x, y + size);
        g.lineTo(x - size, y);
        g.closePath();
    }
}
