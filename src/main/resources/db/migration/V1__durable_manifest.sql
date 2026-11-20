CREATE TABLE transfer (
 id uuid PRIMARY KEY,
 request_key varchar(100) NOT NULL UNIQUE,
 name varchar(200) NOT NULL,
 size bigint NOT NULL CHECK(size>0),
 chunk_size bigint NOT NULL CHECK(chunk_size>0),
 part_count integer NOT NULL CHECK(part_count BETWEEN 1 AND 10000),
 expected_sha256 char(64) NOT NULL,
 status varchar(32) NOT NULL CHECK(status IN ('OPEN','VERIFYING','COMPLETED','FAILED_INTEGRITY','VERIFY_FAILED','CANCELLED','EXPIRED')),
 storage varchar(10) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(),
 expires_at timestamptz NOT NULL,
 verification_attempts integer NOT NULL DEFAULT 0,
 next_verify_at timestamptz NOT NULL DEFAULT now(),
 actual_sha256 char(64),
 last_error varchar(500),
 cleaned boolean NOT NULL DEFAULT false
);
CREATE TABLE part (
 transfer_id uuid NOT NULL REFERENCES transfer(id),
 number integer NOT NULL CHECK(number>0),
 size bigint NOT NULL CHECK(size>0),
 sha256 char(64),
 storage_key varchar(300),
 accepted_at timestamptz,
 PRIMARY KEY(transfer_id,number),
 CHECK((accepted_at IS NULL AND storage_key IS NULL AND sha256 IS NULL) OR (accepted_at IS NOT NULL AND storage_key IS NOT NULL AND sha256 IS NOT NULL))
);
CREATE TABLE transfer_event (
 sequence bigserial PRIMARY KEY,
 transfer_id uuid NOT NULL REFERENCES transfer(id),
 type varchar(40) NOT NULL,
 message varchar(500) NOT NULL,
 part_number integer,
 at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX transfer_work ON transfer(status,next_verify_at);
CREATE INDEX transfer_events ON transfer_event(transfer_id,sequence);
