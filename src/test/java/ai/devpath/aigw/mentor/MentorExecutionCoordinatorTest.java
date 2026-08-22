package ai.devpath.aigw.mentor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

class MentorExecutionCoordinatorTest {

  private static final String CONTEXT_ENVELOPE =
      "{\"snapshotId\":23,\"purpose\":\"mentor_prompt\",\"visibility\":\"private\","
          + "\"fieldsIncluded\":[],\"content\":{}}";
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

    coordinator.start(42L, "queued", null, approvedContext(), emitter);

    verify(persistence, timeout(1000)).saveFailed(42L, "queued", null, "",
        CONTEXT_ENVELOPE, "[]", null, "AI_TIMEOUT");
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

    coordinator.start(42L, "running", 7L, approvedContext(), emitter);
    assertThat(tokenSent.await(1, TimeUnit.SECONDS)).isTrue();
    verify(persistence, timeout(1000)).saveFailed(42L, "running", 7L, "부분 토큰",
        CONTEXT_ENVELOPE, "[]", null, "AI_TIMEOUT");
    release.countDown();
    Thread.sleep(100);

    verify(persistence, never()).saveDone(eq(42L), eq("running"), eq(7L), anyString(),
        anyString(), anyString(), any());
    assertThat(emitter.terminalPayloads()).hasSize(1);
  }

  @Test
  void oneBlockingEmitterCannotDelayAnotherSessionsDeadline() throws Exception {
    ThreadPoolTaskExecutor executor = executor(2, 2, 2);
    ScheduledExecutorService scheduler = scheduler();
    MentorPersistenceService persistence = mock(MentorPersistenceService.class);
    MentorService service = mock(MentorService.class);
    CountDownLatch firstSendEntered = new CountDownLatch(1);
    CountDownLatch releaseFirstSend = new CountDownLatch(1);
    doAnswer(invocation -> {
      String question = invocation.getArgument(0);
      MentorSessionTerminal terminal = invocation.getArgument(2);
      if ("blocking".equals(question)) {
        terminal.sendToken("blocked-token");
      } else {
        while (!Thread.currentThread().isInterrupted()) {
          Thread.onSpinWait();
        }
      }
      return null;
    }).when(service).streamAnswer(anyString(), any(), any());
    MentorExecutionCoordinator coordinator = coordinator(
        service, persistence, executor, scheduler,
        new MentorTimeoutPolicy(Duration.ofMillis(20), Duration.ofMillis(80),
            Duration.ofMillis(250)));

    coordinator.start(1L, "blocking", null, null,
        new BlockingEmitter(firstSendEntered, releaseFirstSend));
    assertThat(firstSendEntered.await(1, TimeUnit.SECONDS)).isTrue();
    RecordingEmitter second = new RecordingEmitter();
    coordinator.start(2L, "silent", null, approvedContext(), second);

    try {
      verify(persistence, timeout(500)).saveFailed(2L, "silent", null, "",
          CONTEXT_ENVELOPE, "[]", null, "AI_TIMEOUT");
      await().atMost(Duration.ofMillis(500)).untilAsserted(() ->
          assertThat(second.terminalPayloads()).singleElement()
              .satisfies(payload -> assertThat(payload).contains("AI_TIMEOUT")));
    } finally {
      releaseFirstSend.countDown();
    }
  }

  @Test
  void blockedSelfEmitterCannotDelayDurableTimeoutCancellationOrAdmissionRelease()
      throws Exception {
    ThreadPoolTaskExecutor workExecutor = executor(1, 1, 1);
    ThreadPoolTaskExecutor terminalExecutor = executor(1, 1, 1);
    ThreadPoolTaskExecutor terminalTransportExecutor = executor(1, 1, 1);
    ThreadPoolTaskExecutor heartbeatExecutor = executor(1, 1, 1);
    MentorTerminalIoDispatcher dispatcher = new MentorTerminalIoDispatcher(
        terminalExecutor, terminalTransportExecutor, heartbeatExecutor, 1);
    ScheduledExecutorService scheduler = scheduler();
    MentorPersistenceService persistence = mock(MentorPersistenceService.class);
    MentorService service = mock(MentorService.class);
    CountDownLatch tokenSendEntered = new CountDownLatch(1);
    CountDownLatch workInterrupted = new CountDownLatch(1);
    CountDownLatch queuedTokenDiscarded = new CountDownLatch(1);
    CountDownLatch releaseTokenSend = new CountDownLatch(1);
    BlockingTerminalEmitter emitter = new BlockingTerminalEmitter(
        tokenSendEntered, workInterrupted, releaseTokenSend);
    doAnswer(invocation -> {
      MentorSessionTerminal terminal = invocation.getArgument(2, MentorSessionTerminal.class);
      Thread.ofVirtual().start(() -> {
        try {
          tokenSendEntered.await();
          terminal.sendToken("queued-token");
        } catch (MentorSessionTerminal.MentorTerminalClosedException expected) {
          queuedTokenDiscarded.countDown();
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
      });
      terminal.sendToken("blocked-token");
      return null;
    }).when(service).streamAnswer(anyString(), any(), any());
    MentorExecutionCoordinator coordinator = new MentorExecutionCoordinator(
        service, persistence, workExecutor, scheduler,
        new MentorTimeoutPolicy(Duration.ofMillis(20), Duration.ofMillis(80),
            Duration.ofMillis(250)), JsonMapper.builder().build(), dispatcher);

    coordinator.start(1L, "blocked-self", null, approvedContext(), emitter);
    assertThat(tokenSendEntered.await(1, TimeUnit.SECONDS)).isTrue();
    // 작업 스레드 기동이 첫 하트비트(provider/2)보다 늦으면 차단 전에 정당한 keepalive 가
    // 배달될 수 있다(CI 부하에서 실측된 flake). 계약은 「차단 중·종결 후 무배달」이므로
    // 차단 진입 시점을 기준선으로 삼는다 — 하트비트 전송은 transport 로 직렬화되어
    // 이 시점 이후 관측값 증가는 곧 억제 실패다.
    int heartbeatsBeforeBlock = emitter.heartbeatAttempts.get();

    verify(persistence, timeout(500)).saveFailed(1L, "blocked-self", null, "",
        CONTEXT_ENVELOPE, "[]", null, "AI_TIMEOUT");
    assertThat(workInterrupted.await(500, TimeUnit.MILLISECONDS)).isTrue();
    assertThat(queuedTokenDiscarded.await(500, TimeUnit.MILLISECONDS)).isTrue();
    await().atMost(Duration.ofMillis(500)).untilAsserted(() ->
        assertThat(dispatcher.availableReservations()).isEqualTo(1));
    assertThat(emitter.terminalAttempts.get()).isZero();

    releaseTokenSend.countDown();
    await().atMost(Duration.ofMillis(500))
        .untilAsserted(() -> assertThat(emitter.terminalAttempts.get()).isEqualTo(1));
    assertThat(emitter.queuedTokenAttempts.get()).isZero();
    assertThat(emitter.heartbeatAttempts.get()).isEqualTo(heartbeatsBeforeBlock);
    verify(persistence, times(1)).saveFailed(1L, "blocked-self", null, "",
        CONTEXT_ENVELOPE, "[]", null, "AI_TIMEOUT");
  }

  @Test
  void silentProviderGetsBoundedKeepalivesUntilExactlyOneTerminal() throws Exception {
    ThreadPoolTaskExecutor executor = executor(1, 1, 1);
    ScheduledExecutorService scheduler = scheduler();
    MentorPersistenceService persistence = mock(MentorPersistenceService.class);
    MentorService service = mock(MentorService.class);
    doAnswer(invocation -> {
      while (!Thread.currentThread().isInterrupted()) {
        Thread.onSpinWait();
      }
      return null;
    }).when(service).streamAnswer(anyString(), any(), any());
    MentorExecutionCoordinator coordinator = coordinator(
        service, persistence, executor, scheduler,
        new MentorTimeoutPolicy(Duration.ofMillis(50), Duration.ofMillis(140),
            Duration.ofMillis(300), Duration.ofMillis(20)));
    RecordingEmitter emitter = new RecordingEmitter();

    coordinator.start(42L, "silent-sensitive-question", null, approvedContext(), emitter);

    await().atMost(Duration.ofMillis(120)).untilAsserted(() ->
        assertThat(emitter.keepalives()).hasSizeGreaterThanOrEqualTo(2));
    verify(persistence, timeout(700)).saveFailed(42L, "silent-sensitive-question", null, "",
        CONTEXT_ENVELOPE, "[]", null, "AI_TIMEOUT");
    await().atMost(Duration.ofMillis(500)).untilAsserted(() ->
        assertThat(emitter.terminalPayloads()).hasSize(1));
    int terminalIndex = emitter.data.indexOf(emitter.terminalPayloads().get(0));
    int keepalivesAtTerminal = emitter.keepalives().size();
    Thread.sleep(80);

    assertThat(emitter.keepalives()).hasSize(keepalivesAtTerminal);
    assertThat(emitter.data.subList(terminalIndex + 1, emitter.data.size()))
        .noneMatch(value -> value.contains("keepalive"));
    assertThat(emitter.keepalives()).allMatch(value -> value.equals(":keepalive\n\n"))
        .noneMatch(value -> value.contains("silent-sensitive-question"));
  }

  private MentorExecutionCoordinator coordinator(MentorService service,
      MentorPersistenceService persistence, ThreadPoolTaskExecutor executor,
      ScheduledExecutorService scheduler, MentorTimeoutPolicy policy) {
    return new MentorExecutionCoordinator(
        service, persistence, executor, scheduler, policy, JsonMapper.builder().build(),
        new MentorTerminalIoDispatcher(
            executor(4, 4, 44), executor(4, 4, 44), executor(4, 4, 44), 48));
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

  private static MentorSnapshotContext approvedContext() {
    return new MentorSnapshotContext(23L, CONTEXT_ENVELOPE,
        "{\"fieldsIncluded\":[],\"content\":{}}");
  }

  static final class RecordingEmitter extends SseEmitter {
    final List<String> data = new CopyOnWriteArrayList<>();

    @Override
    public void send(SseEventBuilder builder) {
      builder.build().forEach(item -> data.add(String.valueOf(item.getData())));
    }

    @Override public void complete() {}

    List<String> terminalPayloads() {
      return data.stream().filter(value -> value.contains("\"status\":" )).toList();
    }

    List<String> keepalives() {
      return data.stream().filter(value -> value.contains("keepalive")).toList();
    }
  }

  static final class BlockingEmitter extends SseEmitter {
    private final CountDownLatch entered;
    private final CountDownLatch release;

    BlockingEmitter(CountDownLatch entered, CountDownLatch release) {
      this.entered = entered;
      this.release = release;
    }

    @Override
    public void send(SseEventBuilder builder) throws IOException {
      boolean token = builder.build().stream()
          .anyMatch(item -> String.valueOf(item.getData()).contains("blocked-token"));
      if (!token) return;
      entered.countDown();
      boolean done = false;
      while (!done) {
        try {
          done = release.await(20, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
          // Emulate an emitter write that does not respond to cancellation.
        }
      }
    }

    @Override public void complete() {}
  }

  static final class BlockingTerminalEmitter extends SseEmitter {
    private final CountDownLatch tokenEntered;
    private final CountDownLatch interrupted;
    private final CountDownLatch release;
    private final AtomicInteger terminalAttempts = new AtomicInteger();
    private final AtomicInteger queuedTokenAttempts = new AtomicInteger();
    private final AtomicInteger heartbeatAttempts = new AtomicInteger();

    BlockingTerminalEmitter(CountDownLatch tokenEntered, CountDownLatch interrupted,
        CountDownLatch release) {
      this.tokenEntered = tokenEntered;
      this.interrupted = interrupted;
      this.release = release;
    }

    @Override
    public void send(SseEventBuilder builder) {
      List<String> values = builder.build().stream()
          .map(item -> String.valueOf(item.getData())).toList();
      if (values.stream().anyMatch(value -> value.contains("blocked-token"))) {
        tokenEntered.countDown();
        boolean done = false;
        while (!done) {
          try {
            done = release.await(20, TimeUnit.MILLISECONDS);
          } catch (InterruptedException ignored) {
            interrupted.countDown();
          }
        }
      }
      if (values.stream().anyMatch(value -> value.contains("\"status\":"))) {
        terminalAttempts.incrementAndGet();
      }
      if (values.stream().anyMatch(value -> value.contains("queued-token"))) {
        queuedTokenAttempts.incrementAndGet();
      }
      if (values.stream().anyMatch(value -> value.contains("keepalive"))) {
        heartbeatAttempts.incrementAndGet();
      }
    }

    @Override public void complete() {}
  }
}
