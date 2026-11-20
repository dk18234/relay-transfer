# Running and operating Relay

## Setup and persistence

`python scripts/init_env.py` generates separate random operator/database credentials without printing them. It preserves an existing `.env` and sets owner-only file permissions. The file is ignored by Git and the Docker build context. Compose consumes it; export `ADMIN_TOKEN` in the CLI shell.

`docker compose up --build -d` serves http://localhost:8082. The app runs as a non-root user and the port is loopback-only. PostgreSQL has no published host port. `docker compose down` keeps named volumes; removing them loses stored state. Restore the database and byte storage consistently, not independently.

## Configuration

| Variable                 | Default                                  | Meaning                                                |
| ------------------------ | ---------------------------------------- | ------------------------------------------------------ |
| `ADMIN_TOKEN`            | required, 24+ characters                 | Operator API/metrics access                            |
| `DATABASE_URL`           | `jdbc:postgresql://localhost:5434/relay` | Native development URL; Compose uses internal hostname |
| `DATABASE_USER`          | `relay`                                  | Database user                                          |
| `DATABASE_PASSWORD`      | development default outside Compose      | Compose requires the generated value                   |
| `DATA_DIRECTORY`         | `./data`, `/data` in container           | Staging and local bytes                                |
| `STORAGE`                | `local`                                  | `local` or `s3`                                        |
| `CHUNK_SIZE`             | 8 MiB                                    | 5–64 MiB, fixed per manifest                           |
| `MAX_FILE_SIZE`          | 32 GiB                                   | Admission limit, not a measured capacity               |
| `MAX_CONCURRENT_UPLOADS` | 4                                        | Per-process upload slots, range 1–8                    |
| `MAX_OPEN_TRANSFERS`     | 20                                       | `OPEN` and `VERIFYING` admission capacity              |
| `EXPIRY_HOURS`           | 24                                       | Fixed window from creation                             |
| `PORT`                   | 8082                                     | Native service port                                    |
| `S3_BUCKET`              | required in S3 mode                      | Existing bucket                                        |
| `AWS_REGION`             | `us-east-1`                              | Bucket region                                          |
| `S3_ENDPOINT`            | empty                                    | Optional compatible/emulator endpoint                  |

## S3 lab and actual AWS

The lab override supplies dummy credentials to Moto 5.2.5 and initializes `relay-lab`. The emulator is in-memory and internal to Compose. Restarting only the app preserves objects; restarting Moto loses them. It does not model all AWS behavior, IAM, durability, or production performance.

For an existing AWS bucket, configure region/default-provider credentials and leave `S3_ENDPOINT` empty. Prefer an IAM role. No production bucket is automatically created. Scope permissions to `relay/*` and the intended bucket: `s3:GetObject`, `s3:PutObject`, `s3:DeleteObject`, `s3:ListBucket`, `s3:AbortMultipartUpload`, `s3:ListMultipartUploadParts`, and `s3:ListBucketMultipartUploads`. Copy needs source GET and destination PUT. This project has not deployed to or tested a paid AWS bucket.

Configure a bucket lifecycle rule to abort incomplete multipart uploads after a suitable interval. Process death can leave one before the SDK gets a chance to abort. Object-prefix cleanup alone cannot remove unfinished MPUs. Do not delete active chunk objects through lifecycle rules before session expiry.

## Recover

Keep PostgreSQL and storage. Restart the app, fetch the manifest, re-hash the original, and send only missing chunks. `VERIFYING` rows are automatically claimed after restart. A lost response may follow a committed receipt; identical retry returns `duplicate: true`.

Storage outages get three finalization attempts. Restore storage, then use the dashboard retry button or `POST /api/transfers/{id}/retry-verification`. An integrity mismatch does not permit replacing accepted bytes or expected hashes. Investigate and start a new manifest.

## Capacity and cleanup

Local storage can temporarily hold staging files, accepted chunks, an assembly file, and a final object. No disk reservation is made at admission. Set limits for available storage and monitor it. Completed objects remain until archived/removed outside the application.

The janitor reclaims `upload-*.tmp` older than 48 hours. Remove crash-left `local/objects/assembly-*.tmp` only while the service is stopped and no finalizer is active. Those files never unlock a download. Prefix cleanup preserves receipt metadata. Receipts are not an externally immutable compliance log; a database operator can edit them.

The dashboard lists 50 recent transfers and 200 recent events per transfer. Browser IndexedDB can be cleared or evicted; it is not a cloud backup. In live mode the browser token stays in memory, not a URL or session file.

## Metrics

```bash
curl -H "X-Admin-Token: $ADMIN_TOKEN" http://localhost:8082/actuator/prometheus
```

Meters include `relay.uploads.active`, chunk accepted/rejected/duplicate counters, accepted bytes, verified/integrity-failed transfers, and storage failures. They reset on app restart and do not replace durable SQL progress. Default logs do not print tokens, request bodies, or file bytes. CORS remains closed; a public deployment requires a separate access-control and operational design.
