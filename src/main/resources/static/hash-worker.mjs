import { hashBlob } from "./sha256.mjs";
self.onmessage = async ({ data }) => {
  try {
    const sha256 = await hashBlob(data.file, (done, total) =>
      self.postMessage({ done, total }),
    );
    self.postMessage({ sha256 });
  } catch (e) {
    self.postMessage({ error: e.message });
  }
};
