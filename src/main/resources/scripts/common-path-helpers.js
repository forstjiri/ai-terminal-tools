// Shared helpers for generated AI Terminal Tools scripts.
// Inlined by the installers into self-contained generated files;
// must not use imports, exports, or template literals.

function aittUnique(values) {
  const seen = new Set();
  return values.filter((value) => value && !seen.has(value) && (seen.add(value), true));
}

function aittExtractPaths(value, result) {
  result = result || [];
  if (!value || typeof value !== "object") return result;
  for (const key of Object.keys(value)) {
    const item = value[key];
    const lower = key.toLowerCase();
    if (typeof item === "string" &&
        ["file", "path", "filepath", "file_path", "filename", "file_name"].includes(lower) &&
        item.length > 0 && item.length < 500 && !item.includes("\n") &&
        item.charAt(0) !== "{" && item.charAt(0) !== "[") {
      result.push(item);
    } else if (Array.isArray(item)) {
      item.forEach((entry) => aittExtractPaths(entry, result));
    } else if (item && typeof item === "object") {
      aittExtractPaths(item, result);
    }
  }
  return result;
}

function aittExtractPatchPaths(patchText) {
  if (typeof patchText !== "string") return [];
  const paths = [];
  for (const line of patchText.split(/\r?\n/)) {
    const match = line.match(/^\*\*\* (?:Add|Update|Delete) File: (.+)$/) ||
      line.match(/^\*\*\* Move to: (.+)$/);
    if (match) paths.push(match[1]);
  }
  return paths;
}

function aittIsWriteToolName(name, writeTools) {
  return writeTools.includes(String(name || ""));
}

// Extract write paths from a raw tool-input JSON string (or patch text).
function aittInputPathsFromText(name, text) {
  if (typeof text !== "string" || !text) return [];
  let parsed = null;
  try { parsed = JSON.parse(text); } catch (_) {}
  if (parsed && typeof parsed === "object") {
    if (typeof parsed.patchText === "string") return aittExtractPatchPaths(parsed.patchText);
    return aittExtractPaths(parsed);
  }
  if (name === "apply_patch" || name === "patch") return aittExtractPatchPaths(text);
  return [];
}
