package ai.devpath.aigw.mentor;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class MentorContextAssemblerTest {

  private MentorContextAssembler assembler() {
    return new MentorContextAssembler();
  }

  @Test
  void projectsOnlyTheApprovedLcsEnvelope() {
    MentorSnapshotContext approved = new MentorSnapshotContext(23L,
        "{\"snapshotId\":23,\"purpose\":\"mentor_prompt\",\"visibility\":\"private\","
            + "\"fieldsIncluded\":[\"current_code\"],\"content\":{\"current_code\":\"print(2)\"}}",
        "{\"fieldsIncluded\":[\"current_code\"],\"content\":{\"current_code\":\"print(2)\"}}");

    MentorContext ctx = assembler().assemble(approved);

    assertThat(ctx.track()).isNull();
    assertThat(ctx.promptText()).isEqualTo(approved.providerContextJson());
    assertThat(ctx.snapshotJson()).isEqualTo(approved.envelopeJson());
  }

  @Test
  void noSnapshotHasExactlyZeroSupplementalContext() {
    MentorContext ctx = assembler().assemble(null);

    assertThat(ctx.track()).isNull();
    assertThat(ctx.promptText()).isEmpty();
    assertThat(ctx.snapshotJson()).isEqualTo("{\"fieldsIncluded\":[],\"content\":{}}");
  }
}
