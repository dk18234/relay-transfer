# Relay — Resumable Dataset Transfers

[![Transfer contracts](https://github.com/dk18234/relay-transfer/actions/workflows/verify.yml/badge.svg)](https://github.com/dk18234/relay-transfer/actions/workflows/verify.yml)

**Lose the connection. Keep the progress. Verify the result.**

Relay transfers synthetic research datasets in independently verified chunks. PostgreSQL records accepted progress; local disk or S3 stores immutable bytes. Restart the client or kill the Java process, then upload only the missing chunks. Download stays locked until the assembled object is independently re-read and its SHA-256 matches the original file.

**[Open the browser sandbox](https://dk18234.github.io/relay-transfer/)** · [Architecture](docs/ARCHITECTURE.md) · [Verification evidence](docs/VERIFICATION.md) · [Learning guide](docs/LEARNING_GUIDE.md)

The public sandbox runs actual hashing and byte storage in your browser, using IndexedDB. It is a separate JavaScript implementation with a 64 MiB file limit; no Java server, PostgreSQL, or S3 runs on GitHub Pages. The same dashboard connects to the full backend when served by the local Java application.

## What is implemented

- Streaming ingestion with a 64 KiB copy buffer and four bounded server upload slots.
- PostgreSQL manifests, fixed part layouts, immutable first-accepted receipts, and request-key idempotency.
- Concurrent out-of-order upload; identical retries return the existing receipt, conflicting content returns 409.
- Durable finalization, ownership through PostgreSQL row locks, and continuation after process death.
- Independent chunk, combined-file, and assembled-object SHA-256 checks. S3 ETags are never treated as full-file hashes.
- Local storage and AWS SDK v2 S3 adapters. S3 assembly uses native multipart copy of immutable chunk objects.
- Cancellation, expiry of open sessions, staged-byte cleanup, and a temporary-file janitor.
- Authenticated API and metrics, live dashboard, Python CLI, and reproducible failure drills.

## Run locally

Requires Docker with Compose and Python 3. Java development uses JDK 21 and the Maven Wrapper.

```bash
git clone https://github.com/dk18234/relay-transfer.git
cd relay-transfer
python scripts/init_env.py
docker compose up --build -d
```

Open **http://localhost:8082**. Copy `ADMIN_TOKEN` from your locally generated `.env` into the connection dialog. It stays in the page's memory. Create a transfer from a file or generate a synthetic sample, start it, pause it, reload, and resume.

The app binds to `127.0.0.1`; PostgreSQL has no published host port. Both named volumes are required for local-mode progress. This is a local operator application, not a multi-user Internet upload service.

### S3 adapter without an AWS account

Use a separate clean lab from local mode, or finish existing transfers first. Sessions cannot silently change storage adapters.

```bash
docker compose -f compose.yaml -f compose.s3-lab.yaml up --build -d
```

The override runs Moto 5.2.5, creates a disposable bucket, and points the actual AWS SDK adapter at its S3 API emulator. Moto is in-memory: app-only restarts preserve progress; restarting Moto loses its objects. This is not production S3.

For a real pre-existing bucket, supply `STORAGE=s3`, `S3_BUCKET`, `AWS_REGION`, and an AWS SDK default-credential-provider source. Leave `S3_ENDPOINT` empty. See [operations](docs/OPERATIONS.md). No paid AWS resources were provisioned.

## CLI: interrupt, resume, independently verify

Export the local credentials into the shell first:

```bash
set -a
. ./.env
set +a

python scripts/relay.py upload /path/to/dataset.bin --session dataset-session.json --pause-after 2

docker compose kill -s SIGKILL app
docker compose up -d app

python scripts/relay.py upload /path/to/dataset.bin --session dataset-session.json --download restored.bin
```

The CLI re-hashes the original, fetches durable receipts, and sends only missing chunks. It holds at most three default 8 MiB upload chunks and streams downloads in 64 KiB blocks. It verifies the downloaded digest before renaming the `.partial` file. Session files contain identity and fingerprints, not the operator token.

The browser hashes originals incrementally in a worker. Chromium's file picker permits live downloads streamed to disk; fallback Blob downloads are capped at 64 MiB. Use the CLI for larger downloads in browsers without that API.

## Verify

```bash
./mvnw -B verify
node --test scripts/browser-domain.test.mjs
node --check src/main/resources/static/app.mjs
```

The Java suite uses real PostgreSQL 16.16. Packaged-process tests run a separate JVM with `-Xmx96m`, force-kill it after two accepted chunks, reject corruption after restart, then kill it again with verification queued. A fresh JVM verifies and serves a byte-identical download.

To include the S3 process test, start Moto and set `S3_ENDPOINT`:

```bash
pip install 'moto[server,s3]==5.2.5'
moto_server -H 127.0.0.1 -p 5000
# In another shell:
export S3_ENDPOINT=http://127.0.0.1:5000
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
./mvnw -B verify
```

Actions runs both adapters, browser-domain checks, and a separate Compose restart smoke. [Raw evidence and caveats](docs/VERIFICATION.md) distinguish actual results from configured limits. Package the public UI with `python scripts/package_demo.py`.

## API

All `/api/` endpoints and `/actuator/prometheus` require `X-Admin-Token`. Only minimal `/actuator/health` is unauthenticated. Tokens are never placed in URLs.

| Method | Endpoint                                 | Purpose                                                |
| ------ | ---------------------------------------- | ------------------------------------------------------ |
| GET    | `/api/config`                            | Adapter and capacity settings                          |
| POST   | `/api/transfers`                         | Create/retrieve a manifest by request key              |
| GET    | `/api/transfers`                         | Latest 50 summaries                                    |
| GET    | `/api/transfers/{id}`                    | Manifest, receipts, latest 200 events                  |
| PUT    | `/api/transfers/{id}/parts/{n}`          | Exact bytes with `Content-Length` and `X-Chunk-SHA256` |
| POST   | `/api/transfers/{id}/complete`           | Durably queue verification                             |
| POST   | `/api/transfers/{id}/retry-verification` | Retry a transient storage failure                      |
| POST   | `/api/transfers/{id}/cancel`             | Close an open/failed session and queue cleanup         |
| GET    | `/api/transfers/{id}/download`           | Stream only a verified object                          |

Create body:

```json
{
  "name": "dataset.bin",
  "size": 25165824,
  "sha256": "<64 hex characters>",
  "requestKey": "research_run_001"
}
```

Responses include 401 authentication; 409 identity/part/state conflict; 411 missing length; 413 admission limit; 422 length/checksum mismatch; 429 bounded capacity with `Retry-After`; and 503 temporary service failure. Inspect JSON `code` and `message`.

## Scope and tradeoffs

The default 32 GiB limit is an **admission setting**, not a benchmark. Process tests use 128 MiB locally and 24 MiB against an S3 emulator. Heap and total RSS are distinct; raw evidence records both. No production throughput or AWS latency is claimed.

The finalizer holds one database transaction while verifying and assembling. This makes ownership and crash rollback explicit, at the cost of a long transaction and a connection per active finalizer. Larger deployments need leased/fenced ownership and a separate publication protocol. Local-mode durability covers tested process restarts with persistent volumes, not power loss, storage loss, or disaster recovery.

SHA-256 establishes byte equality to a declared original, not dataset safety or meaning. The browser can clear or evict its storage. Read [architecture](docs/ARCHITECTURE.md) and [operations](docs/OPERATIONS.md) for remaining boundaries.

Built by **Dinesh Kumar Rama** with AI-assisted implementation. The [reverse-engineering guide](docs/LEARNING_GUIDE.md) provides the path toward independent ownership of the design.
