// Shared env-based event posting for generated AI Terminal Tools scripts.
// Inlined by the installers into self-contained generated files;
// must not use imports, exports, or template literals.
// AITT_SOURCE must be defined by the caller (e.g. const AITT_SOURCE = "opencode";).

function aittEnv(name) {
  if (typeof process !== "undefined" && process && process.env) return process.env[name] || "";
  if (typeof Bun !== "undefined" && Bun && Bun.env) return Bun.env[name] || "";
  return "";
}

const AITT_PORT = aittEnv("AITT_PORT");
const AITT_TOKEN = aittEnv("AITT_TOKEN");
const AITT_TAB_ID = aittEnv("AITT_TAB_ID");

async function aittPost(type, extra) {
  if (!AITT_PORT || !AITT_TOKEN || !AITT_TAB_ID) return;
  if (!extra) extra = {};
  extra.source = AITT_SOURCE;
  extra.type = type;
  extra.tabId = AITT_TAB_ID;
  try {
    await fetch("http://127.0.0.1:" + AITT_PORT + "/event", {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "x-aitt-token": AITT_TOKEN
      },
      body: JSON.stringify(extra)
    });
  } catch (_) {}
}
