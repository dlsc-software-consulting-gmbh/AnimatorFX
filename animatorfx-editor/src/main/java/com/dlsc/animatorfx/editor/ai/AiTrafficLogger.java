package com.dlsc.animatorfx.editor.ai;

import com.dlsc.animatorfx.model.io.JsonWriter;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

final class AiTrafficLogger {
    private static final Logger LOG = Logger.getLogger("com.dlsc.animatorfx.editor.ai.traffic");
    private static final AtomicLong NEXT_ID = new AtomicLong();

    static {
        LOG.setUseParentHandlers(false);
        LOG.setLevel(Level.INFO);
        ConsoleHandler console = new ConsoleHandler();
        console.setLevel(Level.INFO);
        LOG.addHandler(console);
    }

    private AiTrafficLogger() {}

    static long request(AiProviderClient.Provider provider, String method, URI uri, String body, String key) {
        long id = NEXT_ID.incrementAndGet();
        LOG.info("AI request #" + id + " " + provider + " " + method + " "
                + redact(uri.toString(), key) + (body == null ? "" : "\n" + redact(body, key)));
        return id;
    }

    static void response(long id, int status, byte[] body, String key) {
        LOG.info("AI response #" + id + " HTTP " + status + "\n"
                + redact(new String(body, StandardCharsets.UTF_8), key));
    }

    static void failed(long id, Throwable failure, String key) {
        LOG.info("AI request #" + id + " failed: " + redact(AiErrors.message(failure), key));
    }

    private static String redact(String text, String key) {
        if (key == null || key.isEmpty()) return text;
        var forms = new ArrayList<String>();
        forms.add(key);
        forms.add(URLEncoder.encode(key, StandardCharsets.UTF_8));
        String encoded = key;
        for (int i = 0; i < 4; i++) {
            String json = JsonWriter.write(encoded);
            encoded = json.substring(1, json.length() - 1);
            forms.add(encoded);
        }
        forms.sort(Comparator.comparingInt(String::length).reversed());
        for (String form : forms) text = text.replace(form, "[redacted]");
        return text;
    }
}
