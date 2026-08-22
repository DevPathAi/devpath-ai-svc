package ai.devpath.aigw.mentor.eval;

import static org.assertj.core.api.Assertions.assertThat;

import ai.devpath.shared.error.ErrorCode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

class ImmutableSharedResolutionContractTest {

  @Test
  @EnabledIfSystemProperty(named = "immutableSharedContract", matches = "true")
  void cleanRunnerLoadsTheExactSharedJarWithoutCompositeSubstitution() throws Exception {
    Path source = Path.of(ErrorCode.class.getProtectionDomain().getCodeSource().getLocation().toURI());

    assertThat(source).isRegularFile()
        .hasFileName("devpath-shared-0.0.1-et9.20260816.jar");
    assertThat(source.toString().replace('\\', '/'))
        .doesNotContain("/shared-et9/build/");
    assertThat(Files.readString(Path.of("gradle.properties")))
        .contains("devpathSharedVersion=0.0.1-et9.20260816")
        .doesNotContain("SNAPSHOT");
    assertThat(Files.readString(Path.of("build.gradle.kts")))
        .contains("implementation(devpathSharedCoordinate)")
        .doesNotContain("devpath-shared:0.0.1-SNAPSHOT");
  }
}
