package ai.devpath.aigw.mentor;

import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** Mentor orchestration on the admitted worker; terminal state is owned by the request guard. */
@Service
public class MentorService {

  private final MentorContextAssembler contextAssembler;
  private final MentorReferenceService referenceService;
  private final KnowledgeReferenceService knowledgeService;
  private final AiMentorClient mentorClient;
  private final JsonMapper jsonMapper;

  public MentorService(MentorContextAssembler contextAssembler,
      MentorReferenceService referenceService, KnowledgeReferenceService knowledgeService,
      AiMentorClient mentorClient, JsonMapper jsonMapper) {
    this.contextAssembler = contextAssembler;
    this.referenceService = referenceService;
    this.knowledgeService = knowledgeService;
    this.mentorClient = mentorClient;
    this.jsonMapper = jsonMapper;
  }

  /** Runs outside a transaction. Every exit races through one exactly-once terminal guard. */
  public void streamAnswer(String question, MentorSnapshotContext approvedContext,
      MentorSessionTerminal terminal) {
    MentorContext context = contextAssembler.assemble(approvedContext);
    try {
      List<Double> embedding;
      try {
        embedding = referenceService.embedQuestion(question);
      } catch (RuntimeException ignored) {
        embedding = null;
      }

      terminal.throwIfClosed();
      List<SimilarContent> references = embedding == null
          ? List.of() : referenceService.findByEmbedding(embedding, context.track());
      if (!references.isEmpty()) {
        terminal.sendReferences(jsonMapper.writeValueAsString(references));
      }

      terminal.throwIfClosed();
      List<KnowledgeChunk> referenceDocs = embedding == null
          ? List.of() : knowledgeService.findByEmbedding(embedding);
      terminal.throwIfClosed();
      mentorClient.stream(new MentorInput(question, context.promptText(), referenceDocs),
          terminal::sendToken, terminal::selectProvider);
      terminal.completeDone();
    } catch (MentorSessionTerminal.MentorClientDisconnectedException ignored) {
      terminal.completeFailed("CLIENT_ABORTED", "stream aborted");
    } catch (MentorSessionTerminal.MentorTerminalClosedException ignored) {
      // The deadline/client callback already owns persistence and the terminal event.
    } catch (Exception failure) {
      if (isTimeout(failure)) {
        terminal.completeFailed("AI_TIMEOUT", "mentor response timed out");
      } else {
        terminal.completeFailed("AI_PROVIDER_UNAVAILABLE", "mentor response unavailable");
      }
    }
  }

  private static boolean isTimeout(Throwable failure) {
    Throwable current = failure;
    for (int depth = 0; current != null && depth < 12; depth++) {
      if (current instanceof SocketTimeoutException
          || current instanceof HttpTimeoutException
          || current instanceof InterruptedIOException
          || current.getClass().getSimpleName().contains("Timeout")) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }
}
