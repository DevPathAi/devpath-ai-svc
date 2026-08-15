package ai.devpath.aigw.mentor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

class MentorExecutionCoordinatorTest {

  private final List<ThreadPoolTaskExecutor> executors = new ArrayList<>();
  private final List<ScheduledExecutorService> schedulers = new ArrayList<>();

  @AfterEach
  void tearDown() {
    executors.forEach(ThreadPoolTaskExecutor::shutdown);
    schedulers.forEach(ScheduledExecutorService::shutdownNow);
  }

  @Test
  void capacityPlusOneReturnsTypedBusyWithoutRejectedRequestSideEffects() throws Exception {
    ThreadPoolTaskExecutor executor = executor(4, 16, 32);
    ScheduledExecutorService scheduler = scheduler();
    MentorService service = mock(MentorService.class);
    MentorPersistenceService persistence = mock(MentorPersistenceService.class);
    CountDownLatch started = new CountDownLatch(16);
    CountDownLatch release = new CountDownLatch(1);
    doAnswer(invocation -> {
      started.countDown();
      release.await(5, TimeUnit.SECONDS);
      invocation.getArgument(2, MentorSessionTerminal.class).completeDone();
      return null;
    }).when(service).streamAnswer(anyString(), any(), any());
    MentorExecutionCoordinator coordinator = coordinator(
        service, persistence, executor, scheduler,
        new MentorTimeoutPolicy(Duration.ofSeconds(3), Duration.ofSeconds(4), Duration.ofSeconds(5)));

    for (long userId = 1; userId <= 48; userId++) {
      coordinator.start(userId, "q", null, null);
    }
    assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

    assertThatThrownBy(() -> coordinator.start(999L, "rejected", null, null))
        .isInstanceOf(MentorBusyException.class)
        .hasMessage("mentor is busy; retry later");
    verify(persistence, never()).saveDone(eq(999L), anyString(), any(), anyString(),
        anyString(), anyString(), any());
    verify(persistence, never()).saveFailed(eq(999L), anyString(), any(), anyString(),
        anyString(), anyString(), any(), anyString());
    release.countDown();
  }

  @Test
  void queuedRequestTimesOutAndIsCancelledBeforeProviderWork() throws Exception {
    ThreadPoolTaskExecutor executor = executor(1, 1, 1);
    ScheduledExecutorService scheduler = scheduler();
    CountDownLatch occupied = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    executor.submit(() -> {
      occupied.countDown();
      try {
        release.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    });
    assertThat(occupied.await(1, TimeUnit.SECONDS)).isTrue();
    MentorService service = mock(MentorService.class);
    MentorPersistenceService persistence = mock(MentorPersistenceService.class);
    MentorExecutionCoordinator coordinator = coordinator(
        service, persistence, executor, scheduler,
        new MentorTimeoutPolicy(Duration.ofMillis(20), Duration.ofMillis(50), Duration.ofMillis(150)));
    RecordingEmitter emitter = new RecordingEmitter();

    coordinator.start(42L, "queued", null, null, emitter);

    verify(persistence, timeout(1000)).saveFailed(42L, "queued", null, "",
        MentorContextAssembler.EMPTY_CONTEXT_JSON, "[]", null, "AI_TIMEOUT");
    verify(service, never()).streamAnswer(eq("queued"), any(), any());
    assertThat(emitter.terminalPayloads()).singleElement()
        .satisfies(payload -> assertThat(payload).contains("AI_TIMEOUT"));
    release.countDown();
  }

  @Test
  void runningTimeoutPreservesTokenAndLateCompletionCannotOverwriteFailure() throws Exception {
    ThreadPoolTaskExecutor executor = executor(1, 1, 1);
    ScheduledExecutorService scheduler = scheduler();
    MentorPersistenceService persistence = mock(MentorPersistenceService.class);
    MentorService service = mock(MentorService.class);
    CountDownLatch tokenSent = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    doAnswer(invocation -> {
      MentorSessionTerminal terminal = invocation.getArgument(2);
      terminal.sendToken("부분 토큰");
      tokenSent.countDown();
      while (release.getCount() > 0) {
        try {
          release.await(20, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
          // Deliberately emulate a transport that completes late despite cancellation.
        }
      }
      terminal.completeDone();
      return null;
    }).when(service).streamAnswer(anyString(), any(), any());
    MentorExecutionCoordinator coordinator = coordinator(
        service, persistence, executor, scheduler,
        new MentorTimeoutPolicy(Duration.ofMillis(20), Duration.ofMillis(80), Duration.ofMillis(200)));
    RecordingEmitter emitter = new RecordingEmitter();

    coordinator.start(42L, "running", 7L, null, emitter);
    assertThat(tokenSent.await(1, TimeUnit.SECONDS)).isTrue();
    verify(persistence, timeout(1000)).saveFailed(42L, "running", 7L, "부분 토큰",
        MentorContextAssembler.EMPTY_CONTEXT_JSON, "[]", null, "AI_TIMEOUT");
    release.countDown();
    Thread.sleep(100);

    verify(persistence, never()).saveDone(eq(42L), eq("running"), eq(7L), anyString(),
        anyString(), anyString(), any());
    assertThat(emitter.terminalPayloads()).hasSize(1);
  }

  private MentorExecutionCoordinator coordinator(MentorService service,
      MentorPersistenceService persistence, ThreadPoolTaskExecutor executor,
      ScheduledExecutorService scheduler, MentorTimeoutPolicy policy) {
    return new MentorExecutionCoordinator(
        service, persistence, executor, scheduler, policy, JsonMapper.builder().build());
  }

  private ThreadPoolTaskExecutor executor(int core, int max, int queue) {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(core);
    executor.setMaxPoolSize(max);
    executor.setQueueCapacity(queue);
    executor.setThreadNamePrefix("mentor-test-");
    executor.initialize();
    executors.add(executor);
    return executor;
  }

  private ScheduledExecutorService scheduler() {
    ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
    scheduler.setRemoveOnCancelPolicy(true);
    schedulers.add(scheduler);
    return scheduler;
  }

  static final class RecordingEmitter extends SseEmitter {
    final List<String> data = new ArrayList<>();

    @Override
    public void send(SseEventBuilder builder) {
      builder.build().forEach(item -> data.add(String.valueOf(item.getData())));
    }

    @Override public void complete() {}

    List<String> terminalPayloads() {
      return data.stream().filter(value -> value.contains("\"status\":" )).toList();
    }
  }
}
