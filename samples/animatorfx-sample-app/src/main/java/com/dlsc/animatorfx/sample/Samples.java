package com.dlsc.animatorfx.sample;

import com.dlsc.animatorfx.model.io.AnimationFile;
import com.dlsc.animatorfx.model.io.AnimationIO;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.List;

/**
 * The sample animations, which are resources of this application.
 */
public final class Samples {

    /** The samples shown in the browser, by name. */
    public static final List<String> SHOWCASE = List.of("loader", "success", "morph", "card", "orbit");

    /** A single slot that wiggles, used for the in-place demonstration. */
    public static final String ATTENTION = "attention";

    /** The animation used by the live-element interaction demonstration. */
    public static final String LIVE_ELEMENTS = "live-elements";

    private Samples() {
    }

    /**
     * @throws IllegalArgumentException if there is no sample of that name
     */
    public static AnimationFile load(String name) {
        URL url = Samples.class.getResource("animations/" + name + ".afx");
        if (url == null) {
            throw new IllegalArgumentException("No sample named " + name);
        }
        try {
            return AnimationIO.read(url);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
