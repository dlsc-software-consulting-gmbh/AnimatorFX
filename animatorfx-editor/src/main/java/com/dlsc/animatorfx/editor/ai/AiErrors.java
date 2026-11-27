package com.dlsc.animatorfx.editor.ai;

import com.dlsc.animatorfx.model.io.Json;

import javax.net.ssl.SSLException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/** Produces bounded, sanitized diagnostics suitable for display in the AI dialogs. */
public final class AiErrors {
    private AiErrors() {}

    static final class Detail extends RuntimeException {
        Detail(String message) {
            super(message);
        }
    }

    public static String message(Throwable failure) {
        while ((failure instanceof CompletionException || failure instanceof ExecutionException)
                && failure.getCause() != null) {
            failure = failure.getCause();
        }
        int depth = 0;
        for (Throwable cause = failure; cause != null && depth++ < 32; cause = cause.getCause()) {
            if (cause instanceof Detail || cause instanceof AiGenerationService.Failure || cause instanceof AiEditService.Failure
                    || cause instanceof AiCredentialStore.CredentialStoreException) return cause.getMessage();
            if (cause instanceof AccessDeniedException) return "Access to the settings file was denied.";
            if (cause instanceof NoSuchFileException) return "The settings file or its directory does not exist.";
            if (cause instanceof FileAlreadyExistsException) return "A settings file or directory already exists.";
            if (cause instanceof HttpTimeoutException || cause instanceof TimeoutException) return "The request timed out.";
            if (cause instanceof UnknownHostException) return "The provider's hostname could not be resolved (DNS).";
            if (cause instanceof ConnectException) return "Could not connect to the AI provider.";
            if (cause instanceof SSLException) return "The secure connection to the AI provider failed (TLS).";
            if (cause.getCause() == cause) break;
        }
        return "Unexpected error (" + failure.getClass().getSimpleName() + ").";
    }

    static Detail http(int status, byte[] body, String key, String prompt) {
        StringBuilder text = new StringBuilder("The AI provider returned HTTP ").append(status);
        try {
            if (Json.parse(new String(body, StandardCharsets.UTF_8)) instanceof Map<?, ?> root
                    && root.get("error") instanceof Map<?, ?> error) {
                if (error.get("type") instanceof String type && !type.isBlank()) {
                    text.append(" (").append(type).append(')');
                }
                if (error.get("message") instanceof String detail && !detail.isBlank()) {
                    text.append(": ").append(detail);
                }
            }
        } catch (Exception ignored) {
            // Some providers and intermediaries return non-JSON error responses.
        }
        return new Detail(sanitize(text.toString(), key, prompt));
    }

    static String sanitize(String text, String key, String prompt) {
        if (key != null && !key.isBlank()) text = text.replace(key, "[redacted]");
        if (prompt != null && !prompt.isBlank()) text = text.replace(prompt, "[redacted]");
        text = text.replaceAll("(?i)\\bsk-[a-z0-9_-]+", "[redacted]");
        text = text.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", " ");
        return text.length() <= 1500 ? text : text.substring(0, 1500) + "…";
    }
}
