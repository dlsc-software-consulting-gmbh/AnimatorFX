package com.dlsc.animatorfx.editor.ui;

import atlantafx.base.theme.PrimerDark;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.material2.Material2AL;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(ApplicationExtension.class)
class HeadlessToolkitTest {

    private Button button;

    @Start
    void start(Stage stage) {
        Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());
        button = new Button("Hello", new FontIcon(Material2AL.ADD));
        stage.setScene(new Scene(new StackPane(button), 320, 200));
        stage.show();
    }

    @Test
    void showsAStageWithAtlantaFxAndIkonliUnderHeadlessGlass() {
        assertEquals("Headless", System.getProperty("glass.platform"));
        assertTrue(button.getScene().getWindow().isShowing());
        assertTrue(button.getWidth() > 0);
    }
}
