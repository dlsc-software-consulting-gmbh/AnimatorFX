package com.dlsc.animatorfx.sample;

import com.dlsc.animatorfx.player.AnimationPlayer;
import com.dlsc.animatorfx.player.AnimationView;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * An interactive example, not an authentication implementation. The account and its password
 * exist only in this window; no email is sent and no information leaves the application.
 */
final class LoginDemo extends StackPane {

    private static final String DEMO_EMAIL = "demo@example.com";
    private final AnimationView view = new AnimationView(Samples.load("login"));
    private final VBox form = new VBox(10);
    private final Label message = new Label();
    private String password = "animatorfx";

    LoginDemo() {
        getStylesheets().add(LoginDemo.class.getResource("login-demo.css").toExternalForm());
        getStyleClass().add("login-demo");
        form.getStyleClass().add("login-form");
        form.setPadding(new Insets(12, 12, 12, 12));
        message.setId("login-message");
        message.setWrapText(true);
        showLogin("");
        view.bind("login-form", form);
        view.setCycleCount(1);
        view.getPlayer().setOnFinished(event -> {
            view.setCycleCount(AnimationPlayer.INDEFINITE);
            view.playMarker("ambient");
        });
        getChildren().add(view);
        view.playMarker("intro");
    }

    void dispose() {
        view.stop();
        view.dispose();
    }

    private void showLogin(String notice) {
        TextField email = field("login-email", "demo@example.com");
        PasswordField input = passwordField("login-password", "Enter your password");
        Button signIn = primary("sign-in", "Sign in");
        Button forgot = link("forgot-password", "Forgot password?");
        signIn.setOnAction(event -> {
            if (DEMO_EMAIL.equalsIgnoreCase(email.getText().trim()) && password.equals(input.getText())) {
                showSignedIn();
            } else {
                message.setText("Incorrect email or password.");
            }
        });
        input.setOnAction(event -> signIn.fire());
        forgot.setOnAction(event -> showRecovery(email.getText()));
        message.setText(notice);
        form.getChildren().setAll(brand(), title("Welcome back"), subtitle("Sign in to your creative space."),
                label("EMAIL"), email, label("PASSWORD"), input, forgot, signIn, message,
                note("Initial demo login: demo@example.com / animatorfx. No data leaves this window."));
    }

    private void showRecovery(String previousEmail) {
        TextField email = field("recovery-email", "demo@example.com");
        email.setText(previousEmail);
        Button send = primary("send-recovery", "Continue");
        send.setOnAction(event -> {
            if (!DEMO_EMAIL.equalsIgnoreCase(email.getText().trim())) {
                message.setText("Enter the demo account email to continue.");
                return;
            }
            showReset();
        });
        Button back = link("back-to-login", "Back to sign in");
        back.setOnAction(event -> showLogin(""));
        message.setText("");
        form.getChildren().setAll(brand(), title("Forgot password?"),
                subtitle("Enter the demo account email to simulate password recovery."),
                label("EMAIL"), email, send, back, message, note("No recovery email is sent."));
    }

    private void showReset() {
        PasswordField newPassword = passwordField("new-password", "At least 8 characters");
        PasswordField confirm = passwordField("confirm-password", "Repeat the new password");
        Button reset = primary("reset-password", "Update password");
        reset.setOnAction(event -> {
            if (newPassword.getText().length() < 8) {
                message.setText("Use at least 8 characters.");
            } else if (!newPassword.getText().equals(confirm.getText())) {
                message.setText("Passwords do not match.");
            } else {
                password = newPassword.getText();
                showLogin("Password updated for this demo session.");
            }
        });
        Button back = link("reset-back", "Back to sign in");
        back.setOnAction(event -> showLogin(""));
        message.setText("");
        form.getChildren().setAll(brand(), title("Set a new password"),
                subtitle("Choose a password for this demo session."),
                label("NEW PASSWORD"), newPassword, label("CONFIRM PASSWORD"), confirm,
                reset, back, message, note("The password is kept in memory only."));
    }

    private void showSignedIn() {
        Button back = primary("sign-out", "Sign out");
        back.setOnAction(event -> showLogin(""));
        message.setText("Signed in as " + DEMO_EMAIL + " (simulation).");
        form.getChildren().setAll(brand(), title("You're in!"),
                subtitle("Welcome to your creative space."), message, back,
                note("This is an interactive demonstration, not an authentication service."));
    }

    private static Label brand() {
        Label label = new Label("✦  ANIMATORFX");
        label.getStyleClass().add("login-brand");
        return label;
    }

    private static Label title(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("login-title");
        return label;
    }

    private static Label subtitle(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add("login-subtitle");
        return label;
    }

    private static Label label(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("login-label");
        return label;
    }

    private static Label note(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add("login-note");
        return label;
    }

    private static TextField field(String id, String prompt) {
        TextField field = new TextField();
        field.setId(id);
        field.setPromptText(prompt);
        return field;
    }

    private static PasswordField passwordField(String id, String prompt) {
        PasswordField field = new PasswordField();
        field.setId(id);
        field.setPromptText(prompt);
        return field;
    }

    private static Button primary(String id, String text) {
        Button button = new Button(text);
        button.setId(id);
        button.setMaxWidth(Double.MAX_VALUE);
        button.getStyleClass().add("login-primary");
        return button;
    }

    private static Button link(String id, String text) {
        Button button = new Button(text);
        button.setId(id);
        button.setAlignment(Pos.CENTER_LEFT);
        button.getStyleClass().add("login-link");
        return button;
    }
}
