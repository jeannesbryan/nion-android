(() => {
  "use strict";

  const APP = "nion.favicon";
  const MAX_BYTES = 262144;
  let timer = null;
  let lastKey = "";

  function iconUrl() {
    const nodes = document.querySelectorAll(
      'head link[rel~="icon"], head link[rel="shortcut icon"]'
    );

    for (const node of nodes) {
      if (node.href) return node.href;
    }

    try {
      return new URL("/favicon.ico", location.href).href;
    } catch (_) {
      return null;
    }
  }

  function blobAsDataUrl(blob) {
    return new Promise((resolve, reject) => {
      const r = new FileReader();
      r.onload = () => resolve(r.result);
      r.onerror = () => reject(r.error);
      r.readAsDataURL(blob);
    });
  }

  async function publish() {
    const pageUrl = location.href;
    const url = iconUrl();
    if (!url) return;

    const key = pageUrl + "\\n" + url;
    if (key === lastKey) return;
    lastKey = key;

    try {
      const response = await fetch(url, {
        credentials: "include",
        cache: "force-cache"
      });

      if (!response.ok) return;

      const declared = Number(
        response.headers.get("content-length") || "0"
      );
      if (declared > MAX_BYTES) return;

      const blob = await response.blob();
      if (blob.size < 1 || blob.size > MAX_BYTES) return;
      if (location.href !== pageUrl) return;

      const dataUrl = await blobAsDataUrl(blob);
      if (
        typeof dataUrl !== "string" ||
        !dataUrl.startsWith("data:image/")
      ) return;

      await browser.runtime.sendNativeMessage(APP, {
        type: "favicon",
        pageUrl,
        dataUrl
      });
    } catch (_) {
    }
  }

  function schedule() {
    if (timer !== null) clearTimeout(timer);
    timer = setTimeout(() => {
      timer = null;
      publish();
    }, 250);
  }

  schedule();
  addEventListener("pageshow", schedule, { passive: true });

  if (document.head) {
    new MutationObserver(schedule).observe(document.head, {
      subtree: true,
      childList: true,
      attributes: true,
      attributeFilter: ["href", "rel", "sizes", "type"]
    });
  }
})();
