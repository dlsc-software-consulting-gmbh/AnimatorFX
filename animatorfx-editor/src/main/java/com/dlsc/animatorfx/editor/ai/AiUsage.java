package com.dlsc.animatorfx.editor.ai;

import com.dlsc.animatorfx.model.io.Json;

import java.util.Map;

/** Per-job provider-reported token usage, including incomplete accounting. */
public final class AiUsage {

    public record Snapshot(long inputTokens, long outputTokens, boolean inputComplete,
                           boolean outputComplete, int requests) {}

    private long inputTokens;
    private long outputTokens;
    private boolean inputComplete = true;
    private boolean outputComplete = true;
    private int requests;
    private int pending;

    synchronized void begin() {
        requests++;
        pending++;
    }

    synchronized void record(AiProviderClient.Provider provider, String body) {
        pending--;
        Map<?, ?> usage = null;
        try {
            if (body != null && Json.parse(body) instanceof Map<?, ?> root
                    && root.get("usage") instanceof Map<?, ?> values) {
                usage = values;
            }
        } catch (Exception ignored) {
            // Usage cannot be determined from an invalid response.
        }
        Long input = usage == null ? null : count(usage.get("input_tokens"));
        Long output = usage == null ? null : count(usage.get("output_tokens"));
        if (input != null && provider == AiProviderClient.Provider.ANTHROPIC) {
            for (String key : new String[] {"cache_creation_input_tokens", "cache_read_input_tokens"}) {
                if (usage.containsKey(key)) {
                    Long cached = count(usage.get(key));
                    if (cached == null || Long.MAX_VALUE - input < cached) {
                        input = null;
                        break;
                    }
                    input += cached;
                }
            }
        }
        if (input == null || Long.MAX_VALUE - inputTokens < input) inputComplete = false;
        else inputTokens += input;
        if (output == null || Long.MAX_VALUE - outputTokens < output) outputComplete = false;
        else outputTokens += output;
    }

    private static Long count(Object value) {
        if (!(value instanceof Number number)) return null;
        long count = number.longValue();
        return count >= 0 && number.doubleValue() == count ? count : null;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(inputTokens, outputTokens, inputComplete && pending == 0,
                outputComplete && pending == 0, requests);
    }
}
