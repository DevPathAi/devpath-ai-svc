package ai.devpath.aigw.ollama.dto;

import java.util.List;

public record PathGenerateResponse(
    String rationale,
    List<Milestone> milestones
) {
  public record Milestone(
      int weekNum,
      String title,
      String goalDescription,
      List<String> targetSkills,
      int estimatedHours,
      String whyThisOrder,
      String expectedOutcome,
      List<Task> tasks
  ) {
  }

  public record Task(
      int orderNum,
      String taskType,
      String title,
      boolean required
  ) {
  }
}
