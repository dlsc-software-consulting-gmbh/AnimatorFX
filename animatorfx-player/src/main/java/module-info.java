// "module": the editor, named in the qualified export, is not on the module path when this compiles
@SuppressWarnings("module")
module com.dlsc.animatorfx.player {
    requires transitive com.dlsc.animatorfx.model;
    requires transitive javafx.graphics;
    requires transitive javafx.controls;
    requires javafx.media;

    exports com.dlsc.animatorfx.player;
    // the renderer is no API of the library: only the editor draws with its converters
    exports com.dlsc.animatorfx.player.render to com.dlsc.animatorfx.editor;
    // the audio output is no API either: the editor replaces it in its tests and observes scrubbing
    exports com.dlsc.animatorfx.player.internal.audio to com.dlsc.animatorfx.editor;
    exports com.dlsc.animatorfx.player.internal.video to com.dlsc.animatorfx.editor;
}
