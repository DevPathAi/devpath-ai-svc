package ai.devpath.aigw.ollama.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record PathGenerateRequest(
    @NotBlank String track,
    @NotBlank String diagnosedLevel,
    @NotNull List<@NotBlank String> strengthConcepts,
    @NotNull List<@NotBlank String> weaknessConcepts,
    String goal
) {
}
