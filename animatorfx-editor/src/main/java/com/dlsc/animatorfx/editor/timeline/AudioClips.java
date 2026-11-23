package com.dlsc.animatorfx.editor.timeline;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared clip timing for audio and video. The source position at the in-point is
 * the clip offset, the sound runs at the speed of the timeline, stops at its end or starts over
 * when the layer loops. Also the arithmetic of the bar gestures. Pure: times in seconds.
 */
public final class AudioClips {

    /** So many repetitions are worked out at most; a bar never shows more. */
    private static final int MAX_SEGMENTS = 4096;

    /**
     * A stretch of the bar that plays the sound without a break.
     *
     * @param start      the time on the timeline where the stretch starts
     * @param end        the time on the timeline where it ends
     * @param mediaStart the position in the sound at {@code start}
     */
    public record Segment(double start, double end, double mediaStart) {
    }

    /**
     * @param segments the stretches of sound from the in-point on; more than one for a looping layer
     * @param soundEnd the time on the timeline at which the sound stops; the out-point when it
     *                 lasts that long, the in-point when the bar has no sound at all
     */
    public record Layout(List<Segment> segments, double soundEnd) {

        /** The times at which a looping sound starts over. */
        public List<Double> loopBoundaries() {
            List<Double> times = new ArrayList<>();
            for (int i = 1; i < segments.size(); i++) {
                times.add(segments.get(i).start());
            }
            return times;
        }
    }

    /** The in-point and the clip offset after trimming the start of a bar. */
    public record Trim(double inPoint, double clipOffset) {
    }

    private AudioClips() {
    }

    private static boolean known(double clipDuration) {
        return Double.isFinite(clipDuration) && clipDuration > 0;
    }

    /**
     * @param clipDuration the length of the sound; not a positive number when it is not known,
     *                     and the sound then counts as endless
     */
    public static Layout layout(double in, double out, double clipOffset, double clipDuration, boolean loop) {
        List<Segment> segments = new ArrayList<>();
        if (!(out > in)) {
            return new Layout(segments, in);
        }
        if (!known(clipDuration)) {
            segments.add(new Segment(in, out, clipOffset));
            return new Layout(segments, out);
        }
        double offset = loop ? clipOffset % clipDuration : clipOffset;
        if (offset >= clipDuration) {
            return new Layout(segments, in);
        }
        double start = in;
        double media = offset;
        while (start < out && segments.size() < MAX_SEGMENTS) {
            double end = Math.min(out, start + clipDuration - media);
            segments.add(new Segment(start, end, media));
            if (!loop) {
                break;
            }
            start = end;
            media = 0;
        }
        return new Layout(segments, loop ? out : segments.get(0).end());
    }

    /**
     * The position in the sound at a time of the timeline; NaN where the bar has no sound:
     * before the in-point and past the end of a sound that does not loop.
     */
    public static double mediaTime(double time, double in, double clipOffset, double clipDuration, boolean loop) {
        if (time < in) {
            return Double.NaN;
        }
        double media = clipOffset + (time - in);
        if (!known(clipDuration)) {
            return media;
        }
        if (loop) {
            return media % clipDuration;
        }
        return media < clipDuration ? media : Double.NaN;
    }

    /**
     * The clip offset after sliding the sound under its bar by a time span: the sound follows
     * the pointer, so sliding to the right brings an earlier part of it to the in-point. The
     * sound does not slide past its start or, when its length is known, its end.
     */
    public static double slide(double clipOffset, double delta, double clipDuration) {
        if (!Double.isFinite(delta)) {
            return clipOffset;
        }
        double offset = Math.max(0, clipOffset - delta);
        return known(clipDuration) ? Math.min(offset, clipDuration) : offset;
    }

    /**
     * Moves the in-point of a bar and the clip offset with it, so that the sound stays where it
     * is in time. The in-point does not go before the start of the sound or of the document, and
     * leaves the bar at least {@code minLength} long.
     */
    public static Trim trimStart(double in, double out, double clipOffset, double newIn, double minLength) {
        double earliest = Math.max(0, in - clipOffset);
        double latest = Math.max(earliest, out - minLength);
        double moved = Math.clamp(newIn, earliest, latest);
        return new Trim(moved, Math.max(0, clipOffset + (moved - in)));
    }
}
