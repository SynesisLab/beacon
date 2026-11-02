# How Beacon captures server fixtures

Placeholder for M1 (agent.md §13): the procedure for recording real SSE/socket
transcripts from a dev instance into `app/src/test/fixtures/`.

Planned procedure:

1. Start a dev open-webui instance with the toolset under test (display_file,
   frontier-UI card emitter, code interpreter…).
2. Capture the completion stream with `curl -N` and a hand-built request blob
   (fields from `docs/protocol-notes.md`, §chat-completions once M1 lands).
3. Redact instance-specific strings (hostnames, user emails, file ids).
4. Store as `.sse` byte-exact fixtures (`-text binary` in `.gitattributes`);
   golden part-tree JSON next to them.
5. Record socket event transcripts (taskSocket path) as `.ndjson` fixtures.

Status: not yet exercised — no dev instance credentials in scope for M0.