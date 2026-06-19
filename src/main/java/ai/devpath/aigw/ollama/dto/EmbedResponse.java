package ai.devpath.aigw.ollama.dto;

import java.util.List;

public record EmbedResponse(
    List<List<Double>> embeddings
) {
}
