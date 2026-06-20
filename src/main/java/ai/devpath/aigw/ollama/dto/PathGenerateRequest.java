package ai.devpath.aigw.ollama.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record PathGenerateRequest(
    @NotBlank String track,
    @NotBlank String diagnosedLevel,
    @NotEmpty List<@NotBlank String> strengthConcepts,
    @NotEmpty List<@NotBlank String> weaknessConcepts,
    String goal
) {
}
