package ai.devpath.aigw.mentor;

import org.springframework.stereotype.Component;

/** LCS가 승인한 불변 snapshot만 provider/persistence 경계로 투영한다. */
@Component
public class MentorContextAssembler {

  static final String EMPTY_CONTEXT_JSON = "{\"fieldsIncluded\":[],\"content\":{}}";

  public MentorContext assemble(MentorSnapshotContext approvedContext) {
    if (approvedContext == null) {
      return new MentorContext("", EMPTY_CONTEXT_JSON, null);
    }
    return new MentorContext(
        approvedContext.providerContextJson(), approvedContext.envelopeJson(), null);
  }
}
