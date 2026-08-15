# Review event delivery contract

`sandbox.run.submitted` is delivered **at least once**. The sandbox outbox must keep one stable
`eventId` and the `sandboxSessionId` Kafka key across every relay attempt, including the ACK gap
where Kafka accepted a message but the producer did not persist that acknowledgement.

The AI consumer records only `eventId`, `sandboxSessionId`, and delivery timestamps/counts in
`ai_review_event_inbox`; submitted code, stdout, stderr, and the raw Kafka payload are never copied
to the inbox or logs. Payloads produced before `eventId`/`occurredAt` existed remain accepted. They
receive a deterministic identity derived from the event type and sandbox session, so a mixed
rolling deployment still deduplicates them.

`ai_code_reviews` is the provider-effect boundary. One worker atomically changes `PENDING` to
`PROCESSING` with a fenced token and lease. Active duplicates return without calling the provider.
Only that token may write `DONE`/`FAILED`; a stale worker cannot overwrite a newer terminal result.
An expired lease permits recovery after a process crash.
When a redelivered Kafka record finds an unexpired lease, it remains unacknowledged and is retried
until that lease either reaches a terminal state or expires; a restart therefore cannot ACK away the
only recovery trigger.

The database and Kafka boundary cannot prove exactly-once execution if a worker crashes after the
provider accepted a request but before the terminal database update. Providers that expose an
idempotency key should receive the stable review/session correlation in a follow-up adapter change;
the current Claude and Ollama adapters do not expose such a guarantee. The lease prevents normal
duplicate and rolling-consumer calls, but this narrow post-provider crash window remains at-least-once.
