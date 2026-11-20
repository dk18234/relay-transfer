# Reverse-engineering Relay

This implementation was AI-assisted. Use this guide to develop the ability to modify and explain it independently before presenting specific design choices as your own interview experience.

## The explanation to work toward

“Relay is a resumable transfer service for synthetic research datasets. The client hashes the original, and the server creates a manifest fixing its size, SHA-256, and chunk layout. Each chunk is streamed and verified before its immutable storage key and receipt commit in PostgreSQL. After interruption, the client asks which parts were accepted and sends only missing ones. A durable worker checks ordered stored bytes, assembles the object, and re-reads its full hash before unlocking download. Tests kill the actual process and inject corruption. Local and S3 storage share a protocol; S3 was exercised against an emulator.”

Be able to point to and demonstrate every statement rather than memorize it.

## Trace one transfer

| Stop        | Code                                   | Question                                       |
| ----------- | -------------------------------------- | ---------------------------------------------- |
| HTTP        | `ApiController.create`                 | What declares the original?                    |
| Admission   | `TransferService.create`               | Why request key and hash?                      |
| Persistence | `V1__durable_manifest.sql`             | Which constraints protect receipts?            |
| Ingestion   | `upload`, `Hashes.copyExact`           | What if a header lies?                         |
| Race        | Parent read lock, part write lock      | Why can different chunks proceed?              |
| Storage     | `Storage`, `LocalStorage`, `S3Storage` | What if SQL rolls back after storage succeeds? |
| Finalize    | `complete`, `verifyOne`                | What survives process death?                   |
| Publish     | `download`                             | Why is an existing object insufficient?        |
| Resume      | `scripts/relay.py`                     | How is the original reselected safely?         |
| UI          | `LiveStore`, `BrowserStore`            | What actually runs on Pages?                   |

## Six sessions

### 1. Observe the protocol

Use the sandbox: inspect accepted/pending chunks, export a receipt, and run interruption and corruption drills. Explain why 8 MiB accepted does not prove a 24 MiB file is complete.

Exercise: predict which check rejects a flipped byte. Confirm the prediction without changing the expected digest.

### 2. Read the database

Run Compose, then `docker compose exec postgres psql -U relay -d relay`. Inspect the three tables and compare their rows to a receipt. Explain why part rows exist before bytes arrive.

Exercise: calculate the parts for 17 MiB with 8 MiB chunks: 8, 8, 1. Write the general last-part formula.

### 3. Explain streaming

Read `Hashes.copyExact`: fixed buffer, exact length, part digest, optional full digest/output. Explain why `readAllBytes()` would change memory behavior.

Exercise: reduce the buffer to 16 KiB and rerun the process test. Report observed results, distinguishing heap from total RSS, without inventing performance improvements.

### 4. Reason about duplicate requests

Run duplicate and conflict tests. Draw two requests to one part: one wins, the other checks its digest. Draw two different parts sharing the parent read lock.

Exercise: in a separate branch, remove the part lock and design a conflicting-upload race. Explain why Java `synchronized` cannot protect another instance. Restore the correct implementation before publishing.

### 5. Kill the actual application

Read `ProcessRestartIT` and run Maven verify. Identify committed state before both forced deaths. Explain why `SKIP LOCKED` is not a fairness policy and why this finalizer uses a transaction rather than a timed lease.

Exercise: kill after three accepted chunks and update the resumed count. Add a concurrent-finalizer test without weakening the publication gate.

### 6. Follow S3 assembly

Run the S3 lab. Inspect immutable chunk objects, multipart-copy assembly, final readback, and cleanup. Explain why an ETag is not the original full-file digest.

Exercise: interrupt storage during verification, observe three attempts and `VERIFY_FAILED`, restore storage, and explicitly retry. Explain app restart versus losing Moto's in-memory bucket.

## Interview questions

- Where exactly does a chunk become durable accepted progress?
- What if storage succeeds and SQL fails, or the response is lost after SQL succeeds?
- What if the finalizer dies between object completion and SQL commit?
- Why does finalization lock the parent against uploads?
- Why re-read the final object after already hashing ordered parts?
- Why do valid chunk hashes not prove the declared original full hash?
- What does a long database transaction cost, and how would leases/fencing change it?
- How would you add tenant isolation, quotas, and distributed admission?
- Which sizes and memory values did you actually measure?

## Resume language

Use after you can explain and demonstrate the implementation:

- Built a Java/Spring Boot transfer service with PostgreSQL checkpoints, concurrent chunk ingestion, SHA-256 verification, and local/S3 storage adapters.
- Verified resume after forced process termination and byte-identical downloads for a 128 MiB synthetic file under a 96 MiB Java heap limit.

The second claim concerns heap, not total process memory. Do not claim AWS deployment, production throughput, arbitrary-size benchmarks, or actual research-data usage. Describe AI-assisted implementation honestly if asked; focus on reasoning and changes you understand independently.

## Next extensions

After mastering this code, choose one: user ownership; disk quotas; a leased/fenced finalizer without long transactions; expiring download tickets; actual S3 validation in a user-controlled account; or fault injection during publication. Start each with an invariant and a failure test.
