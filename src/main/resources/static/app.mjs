import { BrowserStore } from "./browser-store.mjs";
import { hashBlob } from "./sha256.mjs";
import {
  MiB,
  acceptedParts,
  acceptedBytes,
  progress,
  missingParts,
  formatBytes,
  label,
  hasRejected,
  escapeHtml as esc,
  validateFile,
  sampleFile,
} from "./domain.mjs";
const $ = (selector) => document.querySelector(selector),
  sandbox = document.documentElement.dataset.mode === "browser";
let store,
  token = "",
  config = { chunkSize: MiB, maxFileSize: 64 * MiB, maxConcurrentUploads: 3 },
  transfers = [],
  selected = null,
  current = null,
  uploading = false,
  pause = false,
  showAllLog = false,
  drill = "clean",
  rejectedPart = null,
  activeParts = new Set(),
  sourceFiles = new Map(),
  pendingFile = null,
  pendingSample = false,
  pendingHash = null,
  polling = false;
let toastTimer, lastDetailSignature;
const toast = (message) => {
  clearTimeout(toastTimer);
  $("#toast").textContent = message;
  $("#toast").classList.remove("hidden");
  toastTimer = setTimeout(() => $("#toast").classList.add("hidden"), 6500);
};
const time = (value) =>
  new Date(value).toLocaleTimeString([], {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false,
  });
const api = async (path, method = "GET", body, headers = {}) => {
  const r = await fetch("./api" + path, {
    method,
    headers: {
      "X-Admin-Token": token,
      ...(body instanceof Blob
        ? {}
        : body
          ? { "Content-Type": "application/json" }
          : {}),
      ...headers,
    },
    body: body instanceof Blob ? body : body ? JSON.stringify(body) : undefined,
  });
  if (!r.ok) {
    let e;
    try {
      e = await r.json();
    } catch {
      e = { message: "Service returned " + r.status };
    }
    throw Object.assign(new Error(e.message), {
      code: e.code,
      status: r.status,
    });
  }
  return r.headers.get("Content-Type")?.includes("application/json")
    ? r.json()
    : r;
};
class LiveStore {
  list() {
    return api("/transfers");
  }
  get(id) {
    return api("/transfers/" + id);
  }
  create(c) {
    return api("/transfers", "POST", c);
  }
  upload(id, n, blob, sha) {
    return api(`/transfers/${id}/parts/${n}`, "PUT", blob, {
      "X-Chunk-SHA256": sha,
      "Content-Type": "application/octet-stream",
    });
  }
  complete(id) {
    return api(`/transfers/${id}/complete`, "POST");
  }
  cancel(id) {
    return api(`/transfers/${id}/cancel`, "POST");
  }
  retry(id) {
    return api(`/transfers/${id}/retry-verification`, "POST");
  }
  download(id) {
    return api(`/transfers/${id}/download`);
  }
}
const badgeClass = (t) =>
  t.status === "COMPLETED"
    ? ""
    : t.status === "VERIFYING"
      ? "blue"
      : ["FAILED_INTEGRITY", "VERIFY_FAILED"].includes(t.status)
        ? "red"
        : ["CANCELLED", "EXPIRED"].includes(t.status)
          ? "gray"
          : acceptedParts(t) || hasRejected(t)
            ? "amber"
            : "gray";
function renderList() {
  const query = $("#search").value.toLowerCase(),
    filter = $("#status-filter").value;
  const visible = transfers.filter(
    (t) =>
      t.name.toLowerCase().includes(query) &&
      (filter === "all" ||
        filter === t.status ||
        (filter === "failed" &&
          (["FAILED_INTEGRITY", "VERIFY_FAILED"].includes(t.status) ||
            hasRejected(t)))),
  );
  $("#nav-count").textContent = transfers.length;
  $("#stat-total").textContent = transfers.length.toString().padStart(2, "0");
  $("#stat-bytes").textContent = formatBytes(
    transfers.reduce((n, t) => n + acceptedBytes(t), 0),
  );
  $("#stat-verified").textContent = transfers
    .filter((t) => t.status === "COMPLETED")
    .length.toString()
    .padStart(2, "0");
  $("#stat-concurrency").innerHTML =
    `${Math.min(3, config.maxConcurrentUploads)} <span>workers</span>`;
  $("#transfer-count").textContent = visible.length + " datasets";
  $("#transfer-list").innerHTML = visible.length
    ? visible
        .map(
          (t) =>
            `<button class="transfer-row ${t.id === selected ? "selected" : ""}" data-id="${esc(t.id)}" aria-pressed="${t.id === selected}"><span class="dataset-cell"><span class="file-icon">▤</span><span class="dataset-name-wrap"><strong class="dataset-name">${esc(t.name)}</strong><small class="dataset-meta">${t.partCount} chunks · ${esc(t.storage === "browser" ? "IndexedDB" : t.storage === "s3" ? "S3 storage" : "Local storage")}</small></span></span><span class="row-progress"><progress class="progress-track" max="100" value="${progress(t)}" aria-label="Accepted progress"></progress><span>${progress(t)}%</span></span><span class="badge ${badgeClass(t)}">${esc(label(t, uploading && t.id === selected))}</span><span class="size-cell">${formatBytes(t.size)}</span></button>`,
        )
        .join("")
    : `<div class="empty">${transfers.length ? "No datasets match these filters." : "No transfers yet. Create a manifest to get started."}</div>`;
}
function renderDetail(t) {
  current = t;
  const count = acceptedParts(t),
    done = t.status === "COMPLETED",
    open = t.status === "OPEN",
    verifying = t.status === "VERIFYING",
    failed = t.status === "FAILED_INTEGRITY",
    remaining = t.partCount - count;
  const rejectedCurrent =
    rejectedPart ??
    [...t.events]
      .reverse()
      .find(
        (e) =>
          e.type === "CHUNK_REJECTED" &&
          t.parts.some((p) => p.number === e.part && !p.acceptedAt),
      )?.part;
  let banner,
    kind = "";
  if (done) {
    banner =
      "<strong>Original and assembled SHA-256 match.</strong> This file is verified and ready to download.";
    kind = "success";
  } else if (failed) {
    banner =
      "<strong>Integrity check failed.</strong> " +
      esc(
        t.lastError || "The combined bytes do not match the original manifest.",
      ) +
      " Download remains locked.";
    kind = "error";
  } else if (verifying) {
    banner =
      "<strong>All chunks arrived.</strong> Independently verifying the complete object before unlocking download.";
  } else if (t.status === "VERIFY_FAILED") {
    banner =
      "<strong>Storage verification paused.</strong> Retry when storage is available. Accepted receipts are preserved.";
    kind = "error";
  } else if (["CANCELLED", "EXPIRED"].includes(t.status)) {
    banner =
      "<strong>Session closed.</strong> Audit receipts remain available; staged bytes are queued for cleanup.";
  } else if (uploading) {
    banner = `<strong>Sending only missing chunks.</strong> ${count} of ${t.partCount} chunks accepted; each receipt is checksum verified.`;
  } else if (rejectedCurrent) {
    banner = `<strong>Chunk ${rejectedCurrent} was rejected.</strong> The manifest did not advance. Retry the original bytes to continue.`;
    kind = "error";
  } else if (count) {
    banner = `<strong>Your progress is safe.</strong> ${count} chunks are already accepted. Continue with the remaining ${remaining}.`;
  } else {
    banner =
      "<strong>Ready when you are.</strong> The original file fingerprint is captured. Every chunk will be checked before acceptance.";
  }
  const buttons = uploading
    ? '<button class="button secondary" id="pause-upload">Ⅱ Pause transfer</button>'
    : done
      ? '<button class="button primary" id="download-file">Save verified file</button>'
      : open
        ? `<button class="button primary" id="resume-upload">${rejectedCurrent ? "Retry rejected chunk" : count ? "Resume transfer" : "Start transfer"}</button><button class="button ghost" id="cancel-transfer">Cancel</button>`
        : t.status === "VERIFY_FAILED"
          ? '<button class="button primary" id="retry-verify">Retry verification</button>'
          : "";
  const logs = showAllLog ? t.events : t.events.slice(-3);
  const rejected = new Set(
    t.events.filter((e) => e.type === "CHUNK_REJECTED").map((e) => e.part),
  );
  const displayParts = t.parts.length > 120 ? t.parts.slice(0, 120) : t.parts;
  $("#detail").innerHTML =
    `<div class="detail-header"><div class="detail-title"><span class="file-icon">▤</span><div><h2>${esc(t.name)}</h2><div class="detail-sub">${formatBytes(t.size)} · ${formatBytes(t.chunkSize)} per chunk · <span class="file-hash" title="${esc(t.id)}">${esc(t.id.slice(0, 8))}</span></div></div></div><div class="detail-actions">${buttons}<span class="badge ${badgeClass(t)}">${esc(label(t, uploading))}</span></div></div><div class="resume-banner ${kind}"><span>${done ? "✓" : failed || rejectedCurrent ? "◇" : "↻"}</span><div>${banner}</div></div><div class="detail-body"><div class="chunk-panel"><div class="panel-title"><h3>Chunk map</h3><span>${count} / ${t.partCount} accepted</span></div><div class="chunk-grid">${displayParts.map((p) => `<button class="chunk ${p.acceptedAt ? "accepted" : activeParts.has(p.number) ? "active" : rejected.has(p.number) ? "rejected" : ""}" data-part="${p.number}" title="Chunk ${p.number}: ${p.acceptedAt ? "verified" : rejected.has(p.number) ? "rejected; retry required" : "pending"}" aria-label="Inspect chunk ${p.number}, ${p.acceptedAt ? "verified" : rejected.has(p.number) ? "rejected" : "pending"}">${p.number.toString().padStart(2, "0")}</button>`).join("")}</div>${t.parts.length > 120 ? '<p class="subtle">Showing the first 120 chunks. The downloaded receipt contains the complete manifest.</p>' : ""}<div class="legend"><span><i></i> Accepted</span><span><i class="missing"></i> Pending</span><span><i class="reject"></i> Rejected</span></div><p class="chunk-summary"><strong>${formatBytes(acceptedBytes(t))}</strong> accepted of ${formatBytes(t.size)} · ${remaining === 0 ? "Every chunk has a receipt" : remaining + " chunks remaining"}</p><div id="chunk-inspector" class="inspector"><strong>Every square has a story.</strong> Select a chunk to inspect its byte length, receipt, and SHA-256.</div>${sandbox && open ? `<div class="transfer-settings"><label for="drill-select">Failure drill</label><select id="drill-select" ${uploading ? "disabled" : ""}><option value="clean" ${drill === "clean" ? "selected" : ""}>Clean transfer</option><option value="pause" ${drill === "pause" ? "selected" : ""}>Pause after 8 accepted chunks</option><option value="corrupt" ${drill === "corrupt" ? "selected" : ""}>Corrupt the next chunk</option></select></div>` : ""}</div><aside class="integrity-panel"><h3>Integrity checks</h3><div class="checks"><div class="check-row"><span class="check-circle">✓</span><span>Original fingerprint captured</span></div><div class="check-row"><span class="check-circle ${count < t.partCount ? "wait" : ""}">${count === t.partCount ? "✓" : "·"}</span><span>${count} / ${t.partCount} chunk checksums verified</span></div><div class="check-row"><span class="check-circle ${done ? "" : "wait"}">${done ? "✓" : "·"}</span><span>Complete object re-read</span></div></div><div class="hash-card"><p class="hash-label">ORIGINAL · SHA-256</p><p class="hash-value">${esc(t.expectedSha256)}</p><p class="hash-label">ASSEMBLED · SHA-256</p><p class="hash-value">${t.actualSha256 ? esc(t.actualSha256) : verifying ? "Verification in progress…" : "Available after final verification"}</p><div class="verification-result">${done ? "✓ The complete file matches." : failed ? "◇ Mismatch detected. Download is locked." : "Download unlocks after a complete match."}</div></div></aside></div><div class="event-section"><div class="panel-title"><h3>Transfer activity</h3><div class="event-controls"><button class="receipt-link" id="download-receipt">Download receipt</button><button class="log-toggle" id="toggle-log">${showAllLog ? "Recent activity" : "View full event log"} (${t.events.length})</button></div></div><div>${logs.map((e) => `<div class="event-row ${e.type.includes("REJECT") || e.type.includes("FAILED") ? "error" : ""}"><time>${time(e.at)}</time><span class="event-dot"></span><span class="event-text"><span class="event-type">${esc(e.type.replaceAll("_", " "))}${e.part ? " · " + e.part : ""}</span> &nbsp; ${esc(e.message)}</span></div>`).join("")}</div></div>`;
}
async function refresh() {
  if (!store) return;
  transfers = await store.list();
  if (!selected || !transfers.some((t) => t.id === selected))
    selected = transfers[0]?.id;
  if (selected) {
    const t = await store.get(selected);
    const index = transfers.findIndex((v) => v.id === selected);
    if (index >= 0) transfers[index] = t;
    const signature =
      JSON.stringify(t) +
      uploading +
      drill +
      rejectedPart +
      [...activeParts].join(",") +
      showAllLog;
    if (signature !== lastDetailSignature) {
      renderDetail(t);
      lastDetailSignature = signature;
    }
  } else
    $("#detail").innerHTML =
      '<div class="empty">Start a transfer to see its chunk map, checksums, and audit receipts.</div>';
  renderList();
}
function hashInWorker(file, callback = () => {}) {
  return new Promise((resolve, reject) => {
    const worker = new Worker(new URL("./hash-worker.mjs", import.meta.url), {
      type: "module",
    });
    worker.onmessage = ({ data }) => {
      if (data.error) {
        worker.terminate();
        reject(new Error(data.error));
      } else if (data.sha256) {
        worker.terminate();
        resolve(data.sha256);
      } else callback(data.done, data.total);
    };
    worker.onerror = () => {
      worker.terminate();
      reject(
        new Error("The hashing worker could not start. Refresh and retry."),
      );
    };
    worker.postMessage({ file });
  });
}
async function createSample(name, size = 24 * MiB) {
  const file = sampleFile(name, size),
    sha = await hashInWorker(file),
    t = await store.create({
      name: file.name,
      size: file.size,
      sha256: sha,
      requestKey: crypto.randomUUID(),
      sample: true,
    });
  sourceFiles.set(t.id, file);
  return t;
}
async function seed() {
  if ((await store.list()).length) return;
  $("#transfer-count").textContent = "Generating synthetic byte fixtures…";
  const completed = await createSample("microscopy-preview.bin", 4 * MiB);
  const f = sourceFiles.get(completed.id);
  for (let n = 1; n <= completed.partCount; n++) {
    const blob = f.slice((n - 1) * MiB, n * MiB);
    await store.upload(completed.id, n, blob, await hashBlob(blob));
  }
  await store.complete(completed.id);
  const rejected = await createSample("sequencing-lane04.bin", 8 * MiB),
    source = sourceFiles.get(rejected.id),
    blob = source.slice(0, MiB),
    sha = await hashBlob(blob),
    changed = new Uint8Array(await blob.arrayBuffer());
  changed[31] ^= 255;
  try {
    await store.upload(rejected.id, 1, new Blob([changed]), sha);
  } catch (e) {
    if (e.code !== "CHECKSUM_MISMATCH") throw e;
  }
  const paused = await createSample("nmr-spectrum-run17.bin");
  const file = sourceFiles.get(paused.id);
  for (let n = 1; n <= 8; n++) {
    const chunk = file.slice((n - 1) * MiB, n * MiB);
    await store.upload(paused.id, n, chunk, await hashBlob(chunk));
  }
  selected = paused.id;
}
async function getSource(t) {
  let file = sourceFiles.get(t.id);
  if (
    !file &&
    (t.sample || localStorage.getItem("relay.sample." + t.id) === "true")
  )
    file = sampleFile(t.name, t.size);
  if (!file) {
    toast(
      "Select the original file to resume. Its full fingerprint must match.",
    );
    const input = $("#file-input");
    input.value = "";
    return new Promise((resolve, reject) => {
      input.onchange = async () => {
        try {
          file = input.files[0];
          if (!file) throw new Error("No file selected");
          validateFile(t, file, await hashInWorker(file));
          sourceFiles.set(t.id, file);
          resolve(file);
        } catch (e) {
          reject(e);
        } finally {
          input.onchange = null;
          input.oncancel = null;
        }
      };
      input.oncancel = () => {
        input.onchange = null;
        input.oncancel = null;
        reject(
          new Error(
            "File selection cancelled. Your saved progress is unchanged.",
          ),
        );
      };
      input.click();
    });
  }
  validateFile(t, file, await hashInWorker(file));
  sourceFiles.set(t.id, file);
  return file;
}
async function sendWithRetry(t, n, blob, sha) {
  for (let attempt = 0; attempt < 3; attempt++) {
    try {
      return await store.upload(t.id, n, blob, sha);
    } catch (e) {
      if (
        e.code === "CHECKSUM_MISMATCH" ||
        (e.status && ![429, 503, 502, 504].includes(e.status)) ||
        attempt === 2
      )
        throw e;
      await new Promise((resolve) => setTimeout(resolve, 500 * 2 ** attempt));
    }
  }
}
async function runUpload() {
  if (uploading || !current) return;
  const id = current.id;
  uploading = true;
  pause = false;
  rejectedPart = null;
  const startCount = acceptedParts(current);
  await refresh();
  try {
    const t = await store.get(id),
      file = await getSource(t);
    let queue = missingParts(t),
      cursor = 0,
      stopError = null;
    const concurrency = Math.min(
      3,
      config.maxConcurrentUploads,
      drill === "clean" ? 3 : 1,
    );
    let corrupted = false;
    await Promise.all(
      Array.from({ length: concurrency }, async () => {
        while (cursor < queue.length && !pause && !stopError) {
          const n = queue[cursor++];
          activeParts.add(n);
          await refresh();
          let blob = file.slice(
              (n - 1) * t.chunkSize,
              Math.min(n * t.chunkSize, t.size),
            ),
            sha = await hashBlob(blob);
          if (drill === "corrupt" && !corrupted) {
            corrupted = true;
            const bytes = new Uint8Array(await blob.arrayBuffer());
            bytes[0] ^= 255;
            blob = new Blob([bytes]);
          }
          try {
            if (sandbox)
              await new Promise((resolve) => setTimeout(resolve, 230));
            await sendWithRetry(t, n, blob, sha);
            if (
              drill === "pause" &&
              acceptedParts(await store.get(id)) >= Math.max(8, startCount + 1)
            ) {
              pause = true;
              drill = "clean";
            }
          } catch (e) {
            if (e.code === "CHECKSUM_MISMATCH") {
              rejectedPart = n;
              drill = "clean";
            }
            stopError = e;
          } finally {
            activeParts.delete(n);
            await refresh();
          }
        }
      }),
    );
    if (stopError) throw stopError;
    const after = await store.get(id);
    if (acceptedParts(after) === after.partCount) {
      await store.complete(id);
      toast(
        sandbox
          ? "Complete SHA-256 matched. Your verified file is ready."
          : "All parts accepted. The server is verifying the complete object.",
      );
    } else if (pause)
      toast(
        "Transfer paused. Accepted chunks remain saved, including after a page reload.",
      );
  } catch (e) {
    toast(e.message);
  } finally {
    uploading = false;
    activeParts.clear();
    await refresh();
  }
}
function downloadBlob(blob, name) {
  const a = document.createElement("a"),
    url = URL.createObjectURL(blob);
  a.href = url;
  a.download = name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 15000);
}
async function downloadFile() {
  const t = current;
  try {
    if (sandbox) {
      downloadBlob(await store.download(t.id), t.name);
      return;
    }
    if ("showSaveFilePicker" in window) {
      const handle = await window.showSaveFilePicker({ suggestedName: t.name });
      const response = await store.download(t.id),
        writable = await handle.createWritable();
      await response.body.pipeTo(writable);
      toast("Verified file saved.");
    } else if (t.size <= 64 * MiB) {
      downloadBlob(await (await store.download(t.id)).blob(), t.name);
    } else
      toast(
        "For a download larger than 64 MiB in this browser, use the streaming CLI in the repository.",
      );
  } catch (e) {
    if (e.name !== "AbortError") toast(e.message);
  }
}
function route() {
  const name = location.hash.slice(1) || "transfers",
    page = ["transfers", "integrity", "architecture"].includes(name)
      ? name
      : "transfers";
  document
    .querySelectorAll(".page")
    .forEach((p) => p.classList.toggle("hidden", p.id !== page + "-page"));
  document
    .querySelectorAll(".nav-link")
    .forEach((a) => a.classList.toggle("active", a.dataset.page === page));
  $("#breadcrumb").textContent = {
    transfers: "Transfers",
    integrity: "Integrity lab",
    architecture: "How it works",
  }[page];
}
async function prepareFile(file, sample = false) {
  pendingFile = file;
  pendingSample = sample;
  pendingHash = null;
  $("#create-button").disabled = true;
  $("#new-progress").classList.remove("error");
  $("#file-label").textContent = file.name + " · " + formatBytes(file.size);
  try {
    if (file.size < 1 || file.size > config.maxFileSize)
      throw new Error(
        "File must be between 1 byte and " +
          formatBytes(config.maxFileSize) +
          ".",
      );
    pendingHash = await hashInWorker(
      file,
      (done, total) =>
        ($("#new-progress").textContent =
          "Computing SHA-256 · " + Math.round((done / total) * 100) + "%"),
    );
    $("#new-progress").textContent =
      "Fingerprint ready · " + pendingHash.slice(0, 20) + "…";
    $("#create-button").disabled = false;
  } catch (e) {
    $("#new-progress").textContent = e.message;
    $("#new-progress").classList.add("error");
  }
}
document.addEventListener("click", async (e) => {
  const button = e.target.closest("button"),
    row = e.target.closest("[data-id]");
  try {
    if (row) {
      if (uploading) {
        toast("Pause the current upload before switching datasets.");
        return;
      }
      selected = row.dataset.id;
      rejectedPart = null;
      showAllLog = false;
      await refresh();
    }
    if (!button) return;
    if (button.matches("[data-close]")) {
      button.closest("dialog").close();
    }
    if (button.id === "new-transfer") {
      $("#new-dialog").showModal();
    }
    if (button.id === "sample-button") {
      await prepareFile(sampleFile(), true);
    }
    if (button.id === "mode-action")
      sandbox
        ? (location.hash = "architecture")
        : $("#connect-dialog").showModal();
    if (button.id === "resume-upload") await runUpload();
    if (button.id === "pause-upload") {
      pause = true;
      button.disabled = true;
      button.textContent = "Finishing in-flight chunks…";
    }
    if (button.id === "cancel-transfer") {
      await store.cancel(current.id);
      toast("Session cancelled; accepted receipts are retained.");
      await refresh();
    }
    if (button.id === "retry-verify") {
      await store.retry(current.id);
      await refresh();
    }
    if (button.id === "download-file") await downloadFile();
    if (button.id === "toggle-log") {
      showAllLog = !showAllLog;
      renderDetail(current);
    }
    if (button.id === "download-receipt") {
      downloadBlob(
        new Blob(
          [
            JSON.stringify(
              {
                application: "Relay",
                runtime: sandbox
                  ? "Browser sandbox (IndexedDB)"
                  : "Java backend",
                exportedAt: new Date().toISOString(),
                transfer: current,
              },
              null,
              2,
            ),
          ],
          { type: "application/json" },
        ),
        current.name + ".receipt.json",
      );
    }
    if (button.dataset.part) {
      const p = current.parts[Number(button.dataset.part) - 1];
      $("#chunk-inspector").innerHTML =
        `<strong>Chunk ${p.number} · ${formatBytes(p.size)}</strong><br>${p.acceptedAt ? "Accepted " + esc(new Date(p.acceptedAt).toLocaleString()) + '<br><span class="mono">' + esc(p.sha256) + "</span>" : "Pending. No accepted receipt exists for these bytes."}`;
      document
        .querySelectorAll(".chunk")
        .forEach((b) => b.classList.toggle("selected", b === button));
    }
    if (button.dataset.lab) {
      if (!sandbox) {
        toast(
          "Use the public browser sandbox for interactive drills, or the repository’s process-restart script against this backend.",
        );
        return;
      }
      if (uploading) {
        toast("Pause the current upload first.");
        return;
      }
      toast("Generating a synthetic dataset and its fingerprint…");
      const t = await createSample(
        "research-drill-" + Date.now().toString(36) + ".bin",
      );
      selected = t.id;
      drill = button.dataset.lab;
      rejectedPart = null;
      location.hash = "transfers";
      await refresh();
      await runUpload();
    }
  } catch (e) {
    toast(e.message);
  }
});
$("#new-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  if (!pendingFile || !pendingHash) return;
  if (!store) {
    toast("Connect to the local service first.");
    return;
  }
  $("#create-button").disabled = true;
  try {
    const t = await store.create({
      name: pendingFile.name,
      size: pendingFile.size,
      sha256: pendingHash,
      requestKey: crypto.randomUUID(),
      sample: pendingSample,
    });
    sourceFiles.set(t.id, pendingFile);
    if (pendingSample) localStorage.setItem("relay.sample." + t.id, "true");
    selected = t.id;
    pendingFile = null;
    pendingHash = null;
    $("#new-dialog").close();
    await refresh();
    toast("Manifest created. Start the transfer when ready.");
  } catch (e) {
    $("#new-progress").textContent = e.message;
    $("#create-button").disabled = false;
  }
});
$("#file-input").addEventListener("change", (e) => {
  if (!e.target.onchange && e.target.files[0]) prepareFile(e.target.files[0]);
});
$("#connect-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  token = $("#token").value;
  try {
    config = await api("/config");
    store = new LiveStore();
    $("#connect-dialog").close();
    $("#token").value = "";
    $("#mode-badge").textContent =
      config.storage === "s3" ? "Live · S3 storage" : "Live · local storage";
    $("#mode-notice").innerHTML =
      '<span class="notice-icon">◈</span><div><strong>Connected to your Java backend.</strong><span> PostgreSQL stores progress; the server independently verifies every completed object.</span></div>';
    await refresh();
    toast("Connected. Ready for real transfers.");
  } catch (e) {
    token = "";
    $("#connect-error").textContent = e.message;
  }
});
$("#search").addEventListener("input", renderList);
$("#status-filter").addEventListener("change", renderList);
document.addEventListener("change", (e) => {
  if (e.target.id === "drill-select") drill = e.target.value;
});
window.addEventListener("hashchange", route);
route();
async function init() {
  try {
    if (sandbox) {
      $("#mode-badge").textContent = "Browser sandbox";
      $("#mode-notice").innerHTML =
        '<span class="notice-icon">◈</span><div><strong>Real bytes. Browser storage.</strong><span> This sandbox uses IndexedDB and SHA-256. Your files stay on this device. Run locally for the Java / PostgreSQL / S3 service.</span></div><button class="text-button" id="mode-action">How it works</button>';
      store = await new BrowserStore().init();
      await seed();
      for (const t of await store.list())
        if (t.status === "VERIFYING") await store.verify(t.id);
      await refresh();
    } else {
      $("#transfer-list").innerHTML =
        '<div class="empty">Connect with your operator token to inspect live transfers.</div>';
      $("#stat-total").textContent = "—";
      $("#new-transfer").disabled = true;
      $("#connect-dialog").addEventListener("close", () => {
        if (store) $("#new-transfer").disabled = false;
      });
    }
  } catch (e) {
    $("#transfer-list").innerHTML =
      '<div class="empty">' + esc(e.message) + "</div>";
    toast(e.message);
  }
}
setInterval(async () => {
  if (!store || uploading || polling || document.hidden) return;
  polling = true;
  try {
    await refresh();
  } catch (e) {
    if (!sandbox) $("#mode-badge").textContent = "Connection interrupted";
  } finally {
    polling = false;
  }
}, 3000);
init();
