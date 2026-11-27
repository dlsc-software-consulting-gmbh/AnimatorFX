package com.dlsc.animatorfx.editor.ai;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Sends rendered frames and a structured refinement request to an AI provider. */
@FunctionalInterface
public interface AiReviewRequest {
    CompletableFuture<String> review(AiProviderClient.Provider provider, String model, String apiKey,
                                     String context, List<byte[]> frames, Map<String, Object> schema,
                                     String instructions);
}
