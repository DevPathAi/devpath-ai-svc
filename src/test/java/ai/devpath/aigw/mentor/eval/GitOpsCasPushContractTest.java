package ai.devpath.aigw.mentor.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitOpsCasPushContractTest {

  @TempDir Path temp;

  @Test
  void exactEvaluatedShaLeaseFailsClosedWhenRemoteAdvances() throws Exception {
    String script = Files.readString(Path.of(".github/scripts/push-evaluated-gitops.sh"));
    assertThat(script).contains("--force-with-lease=refs/heads/main:$expected_sha")
        .doesNotContain("pull", "rebase", "while", "for ");

    Path remote = temp.resolve("remote.git");
    Path seed = temp.resolve("seed");
    Path deploy = temp.resolve("deploy");
    Path racer = temp.resolve("racer");
    git(temp, "init", "--bare", "--initial-branch=main", remote.toString());
    init(seed);
    Files.writeString(seed.resolve("deployment.yaml"), "image: old\n");
    git(seed, "add", ".");
    git(seed, "commit", "-m", "seed");
    git(seed, "remote", "add", "origin", remote.toString());
    git(seed, "push", "-u", "origin", "main");
    git(temp, "clone", remote.toString(), deploy.toString());
    git(temp, "clone", remote.toString(), racer.toString());
    configure(deploy);
    configure(racer);
    String evaluated = git(deploy, "rev-parse", "HEAD").output().trim();

    Files.writeString(deploy.resolve("deployment.yaml"), "image: evaluated\n");
    git(deploy, "add", ".");
    git(deploy, "commit", "-m", "evaluated deploy");
    Files.writeString(racer.resolve("other.yaml"), "remote: advanced\n");
    git(racer, "add", ".");
    git(racer, "commit", "-m", "remote advance");
    git(racer, "push", "origin", "main");
    String advanced = git(racer, "rev-parse", "HEAD").output().trim();

    Result rejected = gitAllowFailure(deploy, "push",
        "--force-with-lease=refs/heads/main:" + evaluated,
        "origin", "HEAD:refs/heads/main");

    assertThat(rejected.exitCode()).isNotZero();
    assertThat(git(temp, "--git-dir=" + remote, "rev-parse", "refs/heads/main")
        .output().trim()).isEqualTo(advanced);
  }

  private void init(Path repository) throws Exception {
    git(temp, "init", "--initial-branch=main", repository.toString());
    configure(repository);
  }

  private void configure(Path repository) throws Exception {
    git(repository, "config", "user.name", "contract-test");
    git(repository, "config", "user.email", "contract@example.invalid");
    git(repository, "config", "commit.gpgsign", "false");
  }

  private Result git(Path directory, String... arguments) throws Exception {
    Result result = gitAllowFailure(directory, arguments);
    assertThat(result.exitCode()).as(result.output()).isZero();
    return result;
  }

  private Result gitAllowFailure(Path directory, String... arguments) throws Exception {
    List<String> command = new ArrayList<>();
    command.add("git");
    command.addAll(Arrays.asList(arguments));
    Process process = new ProcessBuilder(command)
        .directory(directory.toFile())
        .redirectErrorStream(true)
        .start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    return new Result(process.waitFor(), output);
  }

  private record Result(int exitCode, String output) {}
}
