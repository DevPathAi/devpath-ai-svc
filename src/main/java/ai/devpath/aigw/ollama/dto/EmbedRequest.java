package ai.devpath.aigw.ollama.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record EmbedRequest(
    @NotEmpty List<@NotBlank String> texts
) {
}
