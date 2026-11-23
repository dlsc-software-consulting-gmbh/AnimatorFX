package com.dlsc.animatorfx.player.internal.video;

import com.dlsc.animatorfx.model.Animation;
import com.dlsc.animatorfx.model.AudioLayer;
import com.dlsc.animatorfx.model.GroupLayer;
import com.dlsc.animatorfx.model.Layer;
import com.dlsc.animatorfx.model.Mask;
import com.dlsc.animatorfx.model.VideoLayer;
import com.dlsc.animatorfx.player.Frame;
import com.dlsc.animatorfx.player.LayerState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Pure video timing; mute never changes the picture's transport. */
public final class VideoSchedule {
    private VideoSchedule() {
    }

    public static List<VideoTarget> targets(Animation animation, Frame frame, boolean playing, double rate,
                                             boolean reversing, boolean muted, double masterVolume,
                                             Map<String, Double> durations) {
        List<VideoTarget> out = new ArrayList<>();
        collect(animation.layers(), frame, true, playing && !reversing && rate > 0 && rate <= 8,
                rate, muted, limit(masterVolume), durations, out);
        return out;
    }

    private static void collect(List<Layer> layers, Frame frame, boolean parentVisible, boolean playing,
                                double rate, boolean muted, double master, Map<String, Double> durations,
                                List<VideoTarget> out) {
        Map<String, Boolean> maskPictures = new HashMap<>();
        for (Layer layer : layers) {
            Mask mask = layer.base().mask();
            if (mask != null && !(layer instanceof AudioLayer)) {
                maskPictures.merge(mask.layerId(), mask.mode() != Mask.Mode.CLIP, Boolean::logicalOr);
            }
        }
        for (Layer layer : layers) {
            LayerState state = frame.get(layer.id());
            boolean visible = parentVisible && state != null && state.visible()
                    && frame.time() >= layer.base().inPoint() && frame.time() < layer.base().outPoint();
            boolean mask = maskPictures.containsKey(layer.id());
            visible &= !mask || maskPictures.get(layer.id());
            if (layer instanceof GroupLayer group) {
                collect(group.children(), frame, visible, playing, rate, muted || mask, master, durations, out);
            } else if (layer instanceof VideoLayer video) {
                double sourceTime = Math.max(0, video.clipOffset() + frame.time() - video.base().inPoint());
                Double duration = durations.get(video.assetId());
                if (duration != null && sourceTime >= duration) {
                    if (video.loop() && duration > 0) {
                        sourceTime %= duration;
                    } else {
                        visible = false;
                    }
                }
                boolean advancing = visible && playing;
                double volume = advancing && !muted && !mask && !video.muted() ? limit(state.volume()) * master : 0;
                out.add(new VideoTarget(video.id(), video.assetId(), visible, advancing, sourceTime,
                        volume, rate, video.loop()));
            }
        }
    }

    private static double limit(double value) {
        return value >= 1 ? 1 : value > 0 ? value : 0;
    }
}
