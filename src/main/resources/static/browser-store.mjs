import { hashBlob } from "./sha256.mjs";
import { MiB, acceptedParts } from "./domain.mjs";
const fail = (code, message) => Object.assign(new Error(message), { code });
const event = (t, type, message, part) => {
  t.events.push({
    sequence: (t.events.at(-1)?.sequence ?? 0) + 1,
    type,
    message,
    part,
    at: new Date().toISOString(),
  });
  t.events = t.events.slice(-200);
  t.updatedAt = new Date().toISOString();
};
export class BrowserStore {
  async init() {
    this.db = await new Promise((resolve, reject) => {
      const r = indexedDB.open("relay-sandbox-v1", 1);
      r.onupgradeneeded = () => {
        r.result.createObjectStore("transfers", { keyPath: "id" });
        r.result.createObjectStore("bytes");
      };
      r.onsuccess = () => resolve(r.result);
      r.onerror = () =>
        reject(
          new Error(
            "Local storage is unavailable. Allow site storage or run the full application locally.",
          ),
        );
    });
    return this;
  }
  transaction(stores, mode, action) {
    return new Promise((resolve, reject) => {
      const tx = this.db.transaction(stores, mode);
      let result;
      tx.oncomplete = () => resolve(result);
      tx.onabort = () =>
        reject(tx.error ?? new Error("Storage transaction was interrupted"));
      tx.onerror = () => {};
      try {
        action(tx, (v) => (result = v));
      } catch (e) {
        tx.abort();
        reject(e);
      }
    });
  }
  async list() {
    const all = await this.transaction(
      ["transfers"],
      "readonly",
      (tx, done) => {
        const r = tx.objectStore("transfers").getAll();
        r.onsuccess = () => done(r.result);
      },
    );
    return all.sort((a, b) => b.createdAt.localeCompare(a.createdAt));
  }
  async get(id) {
    return this.transaction(["transfers"], "readonly", (tx, done) => {
      const r = tx.objectStore("transfers").get(id);
      r.onsuccess = () => done(r.result);
    });
  }
  async mutate(id, action, includeBytes = false) {
    let error;
    const result = await this.transaction(
      includeBytes ? ["transfers", "bytes"] : ["transfers"],
      "readwrite",
      (tx, done) => {
        const s = tx.objectStore("transfers"),
          r = s.get(id);
        r.onsuccess = () => {
          try {
            const t = r.result;
            if (!t) throw fail("NOT_FOUND", "Transfer not found");
            const result = action(
              t,
              includeBytes ? tx.objectStore("bytes") : null,
            );
            s.put(t);
            done(result ?? t);
          } catch (e) {
            error = e;
          }
        };
      },
    );
    if (error) throw error;
    return result;
  }
  async create(c) {
    if (
      !c.name ||
      c.size < 1 ||
      c.size > 64 * MiB ||
      !/^[a-f0-9]{64}$/.test(c.sha256)
    )
      throw fail(
        "INVALID_REQUEST",
        "Browser sandbox supports files up to 64 MiB. Use the local backend for larger files.",
      );
    const existing = (await this.list()).find(
      (t) => t.requestKey === c.requestKey,
    );
    if (existing) {
      if (
        existing.name !== c.name ||
        existing.size !== c.size ||
        existing.expectedSha256 !== c.sha256
      )
        throw fail(
          "REQUEST_KEY_CONFLICT",
          "Request key belongs to another file",
        );
      return existing;
    }
    const id = crypto.randomUUID(),
      now = new Date().toISOString(),
      chunkSize = MiB,
      count = Math.ceil(c.size / chunkSize);
    const t = {
      id,
      name: c.name,
      size: c.size,
      chunkSize,
      partCount: count,
      expectedSha256: c.sha256,
      requestKey: c.requestKey,
      status: "OPEN",
      storage: "browser",
      sample: c.sample ?? false,
      createdAt: now,
      updatedAt: now,
      expiresAt: new Date(Date.now() + 86400000).toISOString(),
      verificationAttempts: 0,
      parts: Array.from({ length: count }, (_, i) => ({
        number: i + 1,
        size: Math.min(chunkSize, c.size - i * chunkSize),
      })),
      events: [],
    };
    event(
      t,
      "CREATED",
      "Browser manifest committed to IndexedDB. No bytes leave this device.",
    );
    let conflict;
    const result = await this.transaction(
      ["transfers"],
      "readwrite",
      (tx, done) => {
        const records = tx.objectStore("transfers"),
          request = records.getAll();
        request.onsuccess = () => {
          const previous = request.result.find(
            (old) => old.requestKey === c.requestKey,
          );
          if (previous) {
            if (
              previous.name !== c.name ||
              previous.size !== c.size ||
              previous.expectedSha256 !== c.sha256
            ) {
              conflict = fail(
                "REQUEST_KEY_CONFLICT",
                "Request key belongs to another file",
              );
              return;
            }
            done(previous);
            return;
          }
          records.put(t);
          done(t);
        };
      },
    );
    if (conflict) throw conflict;
    return result;
  }
  async upload(id, number, blob, sha) {
    const actual = await hashBlob(blob);
    if (actual !== sha) {
      await this.mutate(id, (t) =>
        event(
          t,
          "CHUNK_REJECTED",
          "SHA-256 mismatch. Rejected bytes did not advance the manifest.",
          number,
        ),
      );
      throw fail(
        "CHECKSUM_MISMATCH",
        "Chunk checksum mismatch. Progress is preserved; retry the original bytes.",
      );
    }
    return this.mutate(
      id,
      (t, bytes) => {
        if (t.status !== "OPEN")
          throw fail("NOT_OPEN", "Session is " + t.status);
        const p = t.parts[number - 1];
        if (!p || p.size !== blob.size)
          throw fail(
            "LENGTH_MISMATCH",
            "Chunk does not match the manifest length",
          );
        if (p.acceptedAt) {
          if (p.sha256 !== sha)
            throw fail(
              "PART_CONFLICT",
              "A different verified chunk already occupies this position",
            );
          return { duplicate: true };
        }
        bytes.put(blob, id + ":" + number);
        Object.assign(p, { sha256: sha, acceptedAt: new Date().toISOString() });
        event(
          t,
          "CHUNK_ACCEPTED",
          "SHA-256 verified. Bytes and receipt committed together.",
          number,
        );
        return { duplicate: false };
      },
      true,
    );
  }
  async complete(id) {
    const t = await this.mutate(id, (t) => {
      if (t.status === "COMPLETED" || t.status === "VERIFYING") return t;
      if (t.status !== "OPEN") throw fail("NOT_OPEN", "Session is " + t.status);
      if (acceptedParts(t) !== t.partCount)
        throw fail("PARTS_MISSING", "Some chunks have not been accepted");
      t.status = "VERIFYING";
      event(
        t,
        "VERIFY_QUEUED",
        "All chunks accepted. Complete-file verification started.",
      );
    });
    if (t.status === "COMPLETED") return t;
    return this.verify(id);
  }
  async verify(id) {
    const verify = async () => {
      let t = await this.get(id);
      if (t.status !== "VERIFYING") return t;
      const blobs = [];
      for (const p of t.parts) {
        const blob = await this.transaction(
          ["bytes"],
          "readonly",
          (tx, done) => {
            const r = tx.objectStore("bytes").get(id + ":" + p.number);
            r.onsuccess = () => done(r.result);
          },
        );
        if (!blob || (await hashBlob(blob)) !== p.sha256)
          return this.mutate(id, (t) => {
            t.status = "FAILED_INTEGRITY";
            t.lastError = "Stored chunk is missing or corrupted";
            event(t, "INTEGRITY_FAILED", t.lastError, p.number);
          });
        blobs.push(blob);
      }
      const object = new Blob(blobs, { type: "application/octet-stream" }),
        actual = await hashBlob(object);
      return this.mutate(
        id,
        (t, bytes) => {
          t.actualSha256 = actual;
          t.verificationAttempts++;
          if (actual !== t.expectedSha256) {
            t.status = "FAILED_INTEGRITY";
            t.lastError = "Combined SHA-256 differs from the original file";
            event(t, "INTEGRITY_FAILED", t.lastError);
            return t;
          }
          bytes.put(object, id + ":object");
          for (const p of t.parts) bytes.delete(id + ":" + p.number);
          t.status = "COMPLETED";
          event(
            t,
            "VERIFIED",
            "Full SHA-256 matches the original manifest. Verified download unlocked.",
          );
          return t;
        },
        true,
      );
    };
    return navigator.locks
      ? navigator.locks.request("relay-verify-" + id, verify)
      : verify();
  }
  async cancel(id) {
    return this.mutate(
      id,
      (t, bytes) => {
        if (t.status === "CANCELLED") return t;
        if (!["OPEN", "FAILED_INTEGRITY", "VERIFY_FAILED"].includes(t.status))
          throw fail(
            "CANNOT_CANCEL",
            "This session cannot be cancelled while verifying or after completion",
          );
        t.status = "CANCELLED";
        for (const p of t.parts) bytes.delete(id + ":" + p.number);
        event(
          t,
          "CANCELLED",
          "Session closed. Staged bytes removed; receipts retained.",
        );
        return t;
      },
      true,
    );
  }
  async download(id) {
    const t = await this.get(id);
    if (t.status !== "COMPLETED")
      throw fail("DOWNLOAD_LOCKED", "File has not passed SHA-256 verification");
    return this.transaction(["bytes"], "readonly", (tx, done) => {
      const r = tx.objectStore("bytes").get(id + ":object");
      r.onsuccess = () => done(r.result);
    });
  }
}
