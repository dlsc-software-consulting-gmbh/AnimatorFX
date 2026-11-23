package com.dlsc.animatorfx.editor.ui;

import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HeaderBar;
import javafx.scene.layout.HeaderDragType;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.material2.Material2MZ;

/**
 * The window header: application identity, document tabs and the theme switch, alongside the
 * system-provided window buttons.
 */
public final class HeaderBarView extends HeaderBar {

    public HeaderBarView(DocumentTabs tabs, Theme theme, Image icon) {
        getStyleClass().add("header-bar");

        ImageView image = new ImageView(icon);
        image.setFitWidth(20);
        image.setFitHeight(20);
        image.setPreserveRatio(true);
        Label brand = new Label("AnimatorFX", image);
        brand.getStyleClass().add("brand");
        brand.setGraphicTextGap(8);
        brand.setMinWidth(USE_PREF_SIZE);
        setDragType(brand, HeaderDragType.DRAGGABLE_SUBTREE);
        setLeft(brand);

        ScrollPane documents = new ScrollPane(tabs);
        documents.getStyleClass().add("header-tabs");
        documents.setFitToHeight(true);
        documents.setFitToWidth(true);
        documents.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        documents.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        documents.setPannable(true);
        documents.setFocusTraversable(false);
        documents.setMinWidth(0);
        documents.setMaxHeight(32);
        setDragType(tabs, HeaderDragType.DRAGGABLE);
        setAlignment(documents, Pos.CENTER_LEFT);
        setMargin(documents, new Insets(0, 16, 0, 16));
        setCenter(documents);

        FontIcon themeIcon = new FontIcon();
        themeIcon.iconCodeProperty().bind(Bindings.when(theme.darkProperty())
                .then((Ikon) Material2MZ.NIGHTS_STAY).otherwise(Material2MZ.WB_SUNNY));
        Button themeToggle = new Button(null, themeIcon);
        themeToggle.setId("theme-toggle");
        themeToggle.getStyleClass().addAll("icon-button", "pill");
        themeToggle.setFocusTraversable(false);
        themeToggle.setTooltip(new Tooltip("Switch between dark and light"));
        themeToggle.setOnAction(e -> theme.toggle());
        setRight(themeToggle);
    }
}
