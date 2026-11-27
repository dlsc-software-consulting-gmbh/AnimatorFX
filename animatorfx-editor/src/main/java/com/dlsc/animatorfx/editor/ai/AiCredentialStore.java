package com.dlsc.animatorfx.editor.ai;

import com.microsoft.credentialstorage.SecretStore;
import com.microsoft.credentialstorage.StorageProvider;
import com.microsoft.credentialstorage.model.StoredCredential;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Stores AI provider keys only in the operating system's credential store.
 * A missing credential is reported as unavailable, because some native backends cannot
 * distinguish a missing item from an access failure.
 */
public final class AiCredentialStore {
    private static final String TARGET_PREFIX = "AnimatorFX.ai.";
    private final Backend backend;

    public AiCredentialStore() {
        this(new NativeBackend());
    }

    AiCredentialStore(Backend backend) {
        this.backend = Objects.requireNonNull(backend);
    }

    public String get(String provider) {
        String target = target(provider);
        try {
            String encoded = backend.get(target);
            if (encoded == null) throw new IllegalStateException();
            byte[] decoded = Base64.getDecoder().decode(encoded);
            try {
                String key = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(decoded)).toString();
                if (key.isBlank()) throw new IllegalStateException();
                return key;
            } finally {
                Arrays.fill(decoded, (byte) 0);
            }
        } catch (Exception | LinkageError e) {
            throw unavailable("read");
        } catch (Error e) {
            rethrowFatal(e);
            throw unavailable("read");
        }
    }

    public void put(String provider, String key) {
        String target = target(provider);
        if (key == null || key.isBlank()) throw new IllegalArgumentException("A non-blank API key is required.");
        byte[] bytes;
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(key));
            bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("The API key contains invalid characters.");
        }
        try {
            String value = Base64.getEncoder().encodeToString(bytes);
            if (value.length() > 2047) throw new IllegalArgumentException("The API key is too long to store.");
            try {
                if (!backend.put(target, value)) throw new IllegalStateException();
            } catch (Exception | LinkageError e) {
                throw unavailable("save");
            } catch (Error e) {
                rethrowFatal(e);
                throw unavailable("save");
            }
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    public void delete(String provider) {
        String target = target(provider);
        try {
            if (!backend.delete(target)) throw new IllegalStateException();
        } catch (Exception | LinkageError e) {
            throw unavailable("delete");
        } catch (Error e) {
            rethrowFatal(e);
            throw unavailable("delete");
        }
    }

    private static String target(String provider) {
        if (provider == null || !provider.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalArgumentException("A valid provider identifier is required.");
        }
        try {
            return TARGET_PREFIX + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(provider.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw unavailable("access");
        }
    }

    private static CredentialStoreException unavailable(String operation) {
        return new CredentialStoreException("Unable to " + operation
                + " the API key in the operating system credential store. It may be missing, locked, or unavailable.");
    }

    private static void rethrowFatal(Error error) {
        if (error instanceof VirtualMachineError fatal) throw fatal;
        if (error instanceof ThreadDeath fatal) throw fatal;
    }

    public static final class CredentialStoreException extends RuntimeException {
        private CredentialStoreException(String message) {
            super(message);
        }
    }

    interface Backend {
        String get(String target);
        boolean put(String target, String value);
        boolean delete(String target);
    }

    private static final class NativeBackend implements Backend {
        private SecretStore<StoredCredential> store() {
            SecretStore<StoredCredential> store = StorageProvider.getCredentialStorage(
                    true, StorageProvider.SecureOption.REQUIRED);
            if (store == null) throw new IllegalStateException();
            return store;
        }

        @Override
        public String get(String target) {
            StoredCredential credential = store().get(target);
            if (credential == null) return null;
            try {
                return new String(credential.getPassword());
            } finally {
                credential.clear();
            }
        }

        @Override
        public boolean put(String target, String value) {
            char[] password = value.toCharArray();
            try {
                StoredCredential credential = new StoredCredential("AnimatorFX", password);
                try {
                    return store().add(target, credential);
                } finally {
                    credential.clear();
                }
            } finally {
                Arrays.fill(password, '\0');
            }
        }

        @Override
        public boolean delete(String target) {
            return store().delete(target);
        }
    }
}
