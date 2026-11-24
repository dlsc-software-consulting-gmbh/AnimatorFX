module com.dlsc.animatorfx.editor {
    requires com.dlsc.animatorfx.model;
    requires com.dlsc.animatorfx.player;
    requires com.dlsc.animatorfx.importer;
    requires javafx.controls;
    requires javafx.media;
    requires java.net.http;
    requires langchain4j.core;
    requires langchain4j.http.client;
    requires langchain4j.open.ai;
    requires langchain4j.anthropic;
    requires com.microsoft.credentialstorage;
    requires org.slf4j.nop;
    // javax.sound.sampled: exact waveforms of WAV and AIFF files
    requires java.desktop;
    requires atlantafx.base;
    requires com.dlsc.gemsfx;
    requires org.kordamp.ikonli.core;
    requires org.kordamp.ikonli.javafx;
    requires org.kordamp.ikonli.material2;

    exports com.dlsc.animatorfx.editor;
    exports com.dlsc.animatorfx.editor.ai;
    exports com.dlsc.animatorfx.editor.audio;
    exports com.dlsc.animatorfx.editor.canvas;
    exports com.dlsc.animatorfx.editor.canvas.tools;
    exports com.dlsc.animatorfx.editor.doc;
    exports com.dlsc.animatorfx.editor.inspector;
    exports com.dlsc.animatorfx.editor.inspector.fields;
    exports com.dlsc.animatorfx.editor.inspector.sections;
    exports com.dlsc.animatorfx.editor.session;
    exports com.dlsc.animatorfx.editor.timeline;
    exports com.dlsc.animatorfx.editor.ui;

    opens com.dlsc.animatorfx.editor;
}
