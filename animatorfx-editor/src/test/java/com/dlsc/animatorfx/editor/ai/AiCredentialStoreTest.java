package com.dlsc.animatorfx.editor.ai;

import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AiCredentialStoreTest {

    @Test
    void roundTripsEncodedKeysIndependentlyForEachProvider() {
        MemoryBackend backend = new MemoryBackend();
        AiCredentialStore store = new AiCredentialStore(backend);
        String key = "test-\"key\n\u2603";
        store.put("provider-one", key);
        store.put("provider-two", "other-key");

        assertEquals(key, store.get("provider-one"));
        assertEquals("other-key", store.get("provider-two"));
        assertTrue(backend.values.values().stream().noneMatch(value -> value.contains("\"") || value.contains("\n")));
        assertFalse(backend.values.containsValue(key));
        store.delete("provider-one");
        assertThrows(AiCredentialStore.CredentialStoreException.class, () -> store.get("provider-one"));
        assertEquals("other-key", store.get("provider-two"));
    }

    @Test
    void missingAndFailedOperationsAreUnavailable() {
        MemoryBackend backend = new MemoryBackend();
        AiCredentialStore store = new AiCredentialStore(backend);
        assertThrows(AiCredentialStore.CredentialStoreException.class, () -> store.get("missing"));
        assertThrows(AiCredentialStore.CredentialStoreException.class, () -> store.delete("missing"));
        backend.failWrites = true;
        assertThrows(AiCredentialStore.CredentialStoreException.class, () -> store.put("provider", "dummy"));
    }

    @Test
    void backendErrorsNeverExposeSecretsOrTheirCauses() {
        String secret = "dummy-secret-in-error";
        AiCredentialStore store = new AiCredentialStore(new AiCredentialStore.Backend() {
            public String get(String target) { throw new Error(secret); }
            public boolean put(String target, String value) { throw new IllegalStateException(secret); }
            public boolean delete(String target) { throw new Error(secret); }
        });
        for (Runnable operation : new Runnable[]{
                () -> store.get("provider"), () -> store.put("provider", secret), () -> store.delete("provider")}) {
            AiCredentialStore.CredentialStoreException error =
                    assertThrows(AiCredentialStore.CredentialStoreException.class, operation::run);
            assertNull(error.getCause());
            StringWriter trace = new StringWriter();
            error.printStackTrace(new PrintWriter(trace));
            assertFalse(trace.toString().contains(secret));
        }
    }

    @Test
    void invalidInputsAndMalformedStoredValuesFailSafely() {
        MemoryBackend backend = new MemoryBackend();
        AiCredentialStore store = new AiCredentialStore(backend);
        assertThrows(IllegalArgumentException.class, () -> store.put("", "dummy"));
        assertThrows(IllegalArgumentException.class, () -> store.put("provider", ""));
        store.put("provider", "dummy");
        backend.values.replaceAll((target, value) -> "not valid base64!");
        assertThrows(AiCredentialStore.CredentialStoreException.class, () -> store.get("provider"));
    }

    @Test
    void macOsNativeStoreRoundTripsADummyKeyWhenExplicitlyEnabled() {
        assumeTrue(Boolean.getBoolean("animatorfx.credential.nativeTest")
                && System.getProperty("os.name").equals("Mac OS X"));
        AiCredentialStore store = new AiCredentialStore();
        String provider = "native-test-" + UUID.randomUUID();
        try {
            store.put(provider, "dummy-\"key\n\u2603");
            assertEquals("dummy-\"key\n\u2603", store.get(provider));
        } finally {
            store.delete(provider);
        }
    }

    private static final class MemoryBackend implements AiCredentialStore.Backend {
        private final Map<String, String> values = new HashMap<>();
        private boolean failWrites;

        public String get(String target) {
            return values.get(target);
        }

        public boolean put(String target, String value) {
            if (failWrites) return false;
            values.put(target, value);
            return true;
        }

        public boolean delete(String target) {
            return values.remove(target) != null;
        }
    }
}
