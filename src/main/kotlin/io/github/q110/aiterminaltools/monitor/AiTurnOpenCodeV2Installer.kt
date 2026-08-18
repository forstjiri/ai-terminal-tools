// OpenCode 2 plugin installer — generates the project-level V2 plugin and launcher
package io.github.q110.aiterminaltools.monitor

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import io.github.q110.aiterminaltools.ProjectBasePath
import io.github.q110.aiterminaltools.settings.AiTerminalToolsSettings
import java.nio.file.Files
import java.nio.file.Path

/**
 * OpenCode V2 and V1 use the same project plugin path. Starting V2 intentionally
 * replaces the V1 module because OpenCode loads one project plugin for this id.
 */
class AiTurnOpenCodeV2Installer(
    private val project: Project
) {
    private val log = Logger.getInstance(AiTurnOpenCodeV2Installer::class.java)

    fun installOpenCodeV2Plugin(basePath: Path, tabId: String, token: String, port: Int): AiTerminalLauncher.Paths {
        val validatedBasePath = ProjectBasePath.requireValid(basePath)
        val toolsDir = validatedBasePath.resolve(".idea").resolve("ai-terminal-tools")
        Files.createDirectories(toolsDir)
        writeJsPlugin(validatedBasePath)
        AiTurnOpenCodeV2Bridge.installTab(validatedBasePath, tabId, token, port)
        return writeLauncherScripts(toolsDir, tabId, token, port)
    }

    private fun writeJsPlugin(basePath: Path) {
        val pluginsDir = basePath.resolve(".opencode").resolve("plugins")
        Files.createDirectories(pluginsDir)
        val pluginFile = pluginsDir.resolve("ai-terminal-tools.js")
        // No @opencode-ai/plugin import: Plugin.define() is an identity helper, so a plain
        // { id, setup } default export is equivalent and needs no dependency install.
        //
        // Config is file-based (.idea/ai-terminal-tools/opencode2-bridge.json) because the
        // V2 plugin runs inside the shared background server, which does not inherit the
        // terminal launcher's AITT_* environment variables. Env vars remain a fallback.
        val js = """
            import { readFile, appendFile, stat, writeFile } from "node:fs/promises";

            function env(name) {
              if (typeof process !== "undefined" && process && process.env) return process.env[name] || "";
              if (typeof Bun !== "undefined" && Bun && Bun.env) return Bun.env[name] || "";
              return "";
            }

            function fileUrlToPath(url) {
              try {
                const parsed = new URL(".", url);
                let dir = decodeURIComponent(parsed.pathname);
                if (/^\/[A-Za-z]:/.test(dir)) dir = dir.slice(1);
                return dir;
              } catch (_) {
                return "";
              }
            }

            function detectProjectRoot(pluginDirectory) {
              if (!pluginDirectory) return "";
              const match = pluginDirectory.match(/^(.*?)[\\/]\.opencode[\\/]plugins[\\/]$/i);
              return match ? match[1] : "";
            }

            const pluginDirectory = fileUrlToPath(import.meta.url);
            const projectRoot = detectProjectRoot(pluginDirectory);
            const toolsDirectory = projectRoot ? projectRoot + "/.idea/ai-terminal-tools" : "";
            const bridgePath = toolsDirectory ? toolsDirectory + "/opencode2-bridge.json" : "";
            const debugPath = (toolsDirectory ? toolsDirectory : pluginDirectory) + "/plugin-debug.log";

            let debugWrites = 0;
            async function debug(line) {
              try {
                debugWrites++;
                if (debugWrites % 64 === 1) {
                  try {
                    const info = await stat(debugPath);
                    if (info.size > 262144) await writeFile(debugPath, "");
                  } catch (_) {}
                }
                await appendFile(debugPath, new Date().toISOString() + " " + line + "\n");
              } catch (_) {}
            }

            function envTarget() {
              const port = env("AITT_PORT");
              const token = env("AITT_TOKEN");
              const tabId = env("AITT_TAB_ID");
              return port && token && tabId ? { port: port, token: token, tabId: tabId } : null;
            }

            let bridgeCache = { mtimeMs: -1, json: null };
            async function loadBridge() {
              if (!bridgePath) return null;
              try {
                const info = await stat(bridgePath);
                if (bridgeCache.mtimeMs !== info.mtimeMs) {
                  const text = await readFile(bridgePath, "utf8");
                  bridgeCache = { mtimeMs: info.mtimeMs, json: JSON.parse(text) };
                }
                return bridgeCache.json;
              } catch (_) {
                return null;
              }
            }

            let noTargetLogged = false;
            async function resolveTarget() {
              const bridge = await loadBridge();
              if (bridge && bridge.port && Array.isArray(bridge.tabs) && bridge.tabs.length) {
                // The newest registered tab receives events; the IDEA side rewrites the
                // bridge file whenever an OpenCode 2 terminal starts or closes.
                const tab = bridge.tabs[bridge.tabs.length - 1];
                if (tab && tab.tabId && tab.token) {
                  return { port: bridge.port, token: tab.token, tabId: tab.tabId, source: "bridge" };
                }
              }
              const fromEnv = envTarget();
              if (fromEnv) return { port: fromEnv.port, token: fromEnv.token, tabId: fromEnv.tabId, source: "env" };
              return null;
            }

            async function post(type, extra) {
              const target = await resolveTarget();
              if (!target) {
                if (!noTargetLogged) {
                  noTargetLogged = true;
                  await debug("post skipped (no bridge file and no AITT_* env): type=" + type);
                }
                return;
              }
              noTargetLogged = false;
              const body = Object.assign({}, extra || {}, { source: "opencode2", type: type, tabId: target.tabId });
              try {
                const response = await fetch("http://127.0.0.1:" + target.port + "/event", {
                  method: "POST",
                  headers: { "content-type": "application/json", "x-aitt-token": target.token },
                  body: JSON.stringify(body)
                });
                await debug("post " + type + " -> HTTP " + response.status + " (target=" + target.source + ")");
              } catch (error) {
                await debug("post " + type + " failed: " + (error && error.message ? error.message : String(error)) + " (target=" + target.source + ")");
              }
            }

            function objectValue(value) {
              return value && typeof value === "object" ? value : null;
            }

            // V2 events may be direct objects, nested envelopes, or SSE { event, data } records.
            function decodeSseEnvelope(raw) {
              const envelope = objectValue(raw);
              if (!envelope || typeof envelope.event !== "string") return envelope;
              let data = envelope.data;
              if (typeof data === "string") {
                try { data = JSON.parse(data); } catch (_) {}
              }
              if (data && typeof data === "object") return Object.assign({}, data, { type: envelope.event });
              return { type: envelope.event, data: data };
            }

            function normalizeEvent(raw) {
              let current = decodeSseEnvelope(raw);
              for (let i = 0; i < 3 && current; i++) {
                if (typeof current.type === "string") return current;
                const nested = objectValue(current.event) || objectValue(current.payload) || objectValue(current.data);
                if (!nested) return current;
                current = decodeSseEnvelope(nested);
              }
              return current;
            }

            function eventType(raw) {
              const event = normalizeEvent(raw) || {};
              return event.type || event.eventType || event.name || "";
            }

            function dataValue(raw) {
              const event = normalizeEvent(raw) || {};
              return objectValue(event.data) || {};
            }

            function sessionId(raw) {
              const event = normalizeEvent(raw) || {};
              const outer = objectValue(raw) || {};
              const data = dataValue(raw);
              const properties = objectValue(event.properties) || {};
              const session = objectValue(properties.session) || objectValue(event.session) || objectValue(data.session);
              return data.sessionID || data.sessionId || data.session_id ||
                properties.sessionID || properties.sessionId || properties.session_id ||
                event.sessionID || event.sessionId || event.session_id ||
                outer.sessionID || outer.sessionId || outer.session_id ||
                (session && (session.id || session.sessionID || session.sessionId)) || "";
            }

            function statusValue(raw) {
              const event = normalizeEvent(raw) || {};
              const data = dataValue(raw);
              const properties = objectValue(event.properties) || {};
              const status = data.status || properties.status || event.status || data.state || properties.state || event.state;
              return typeof status === "string" ? status.toLowerCase() :
                (status && (status.type || status.state || status.status) || "").toLowerCase();
            }

            function eventDirectory(raw) {
              const outer = objectValue(raw) || {};
              const location = objectValue(outer.location);
              return location && typeof location.directory === "string" ? location.directory : "";
            }

            function sameDirectory(left, right) {
              const norm = (value) => String(value || "").replace(/[\\/]+$/, "").toLowerCase();
              return norm(left) === norm(right);
            }

            const sessions = new Map();

            function rememberSession(id, parentID, directory) {
              if (!id || !directory) return null;
              const value = { parentID: parentID || "", directory: directory };
              sessions.set(id, value);
              return value;
            }

            async function resolveById(ctx, id) {
              if (!id) return null;
              const cached = sessions.get(id);
              if (cached) return cached;
              try {
                const result = await ctx.session.get({ sessionID: id });
                const session = (result && result.data) || result;
                const location = session && objectValue(session.location);
                return rememberSession(
                  session && session.id,
                  session && session.parentID,
                  (location && location.directory) || ""
                );
              } catch (error) {
                await debug("session lookup failed id=" + id + ": " + (error && error.message ? error.message : String(error)));
                return null;
              }
            }

            async function resolveSession(ctx, raw, id) {
              if (!id) return null;
              if (eventType(raw) === "session.created") {
                const data = dataValue(raw);
                const location = objectValue(data.location);
                const directory = (location && location.directory) || eventDirectory(raw);
                return rememberSession(id, data.parentID, directory);
              }
              return resolveById(ctx, id);
            }

            // Subagent edits belong to the parent turn; file events are reported under the root session.
            async function rootSessionId(ctx, id) {
              let current = id;
              for (let i = 0; i < 8 && current; i++) {
                const session = await resolveById(ctx, current);
                if (!session || !session.parentID) return current;
                current = session.parentID;
              }
              return id;
            }

            const WRITE_TOOL_NAMES = ["edit", "write", "apply_patch", "patch"];
            const toolCalls = new Map();

            function isWriteToolName(name) {
              return WRITE_TOOL_NAMES.includes(String(name || ""));
            }

            function inputPathsFromText(name, text) {
              if (typeof text !== "string" || !text) return [];
              let parsed = null;
              try { parsed = JSON.parse(text); } catch (_) {}
              if (parsed && typeof parsed === "object") {
                if (typeof parsed.patchText === "string") return extractPatchPaths(parsed.patchText);
                return extractPaths(parsed);
              }
              if (name === "apply_patch" || name === "patch") return extractPatchPaths(text);
              return [];
            }

            function unique(values) {
              const seen = new Set();
              return values.filter((value) => value && !seen.has(value) && (seen.add(value), true));
            }

            function extractPaths(value, result) {
              result = result || [];
              if (!value || typeof value !== "object") return result;
              for (const key of Object.keys(value)) {
                const item = value[key];
                const lower = key.toLowerCase();
                if (typeof item === "string" &&
                    ["file", "path", "filepath", "file_path", "filename", "file_name"].includes(lower) &&
                    item.length < 500 && !item.includes("\n")) {
                  result.push(item);
                } else if (Array.isArray(item)) {
                  item.forEach((entry) => extractPaths(entry, result));
                } else if (item && typeof item === "object") {
                  extractPaths(item, result);
                }
              }
              return result;
            }

            function extractPatchPaths(patchText) {
              if (typeof patchText !== "string") return [];
              const paths = [];
              for (const line of patchText.split(/\r?\n/)) {
                const match = line.match(/^\*\*\* (?:Add|Update|Delete) File: (.+)$/) ||
                  line.match(/^\*\*\* Move to: (.+)$/);
                if (match) paths.push(match[1]);
              }
              return paths;
            }

            function toolName(event) {
              const tool = event && event.tool;
              return typeof tool === "string" ? tool : (tool && (tool.name || tool.id)) || "";
            }

            function writePaths(event) {
              const input = (event && event.input) || {};
              if (toolName(event) === "apply_patch") return unique(extractPatchPaths(input.patchText));
              return unique(extractPaths(input));
            }

            function isWriteTool(event) {
              return ["edit", "write", "apply_patch"].includes(toolName(event));
            }

            function hookSessionId(event) {
              return event && (event.sessionID || event.sessionId || event.session_id) || "";
            }

            export default {
              id: "ai-terminal-tools",
              setup: async (ctx) => {
                const bridgeProbe = await loadBridge();
                await debug(
                  "setup root=" + projectRoot +
                  " bridge=" + (bridgeProbe && Array.isArray(bridgeProbe.tabs) ? "tabs:" + bridgeProbe.tabs.length : "missing") +
                  " env=" + (envTarget() ? "present" : "absent")
                );

                if (ctx.event && typeof ctx.event.subscribe === "function") {
                  void (async () => {
                    let seenEvents = 0;
                    try {
                      for await (const raw of ctx.event.subscribe()) {
                        try {
                          seenEvents++;
                          const type = eventType(raw);
                          const id = sessionId(raw);
                          const lifecycle = type === "session.created" || type === "session.execution.started" ||
                            type === "session.execution.succeeded" || type === "session.execution.failed" ||
                            type === "session.execution.interrupted" || type === "session.idle" || type === "session.status";
                          const toolEvent = type.startsWith("session.tool.");
                          if (seenEvents <= 100 || seenEvents % 100 === 0 || lifecycle || toolEvent) {
                            await debug("event#" + seenEvents + " type=" + type + " id=" + id + " dir=" + eventDirectory(raw));
                          }
                          if (!id) continue;
                          const session = await resolveSession(ctx, raw, id);
                          if (!session || !sameDirectory(session.directory, projectRoot)) {
                            if (lifecycle || toolEvent) await debug("ignored " + type + " id=" + id + " (unknown or other project)");
                            continue;
                          }
                          if (type === "session.tool.input.started") {
                            const data = dataValue(raw);
                            if (data.id) toolCalls.set(data.id, { name: String(data.name || ""), paths: [] });
                            continue;
                          }
                          if (type === "session.tool.input.ended" || type === "session.tool.called") {
                            const data = dataValue(raw);
                            const entry = toolCalls.get(data.id);
                            if (!entry || !isWriteToolName(entry.name)) continue;
                            const paths = type === "session.tool.called"
                              ? extractPaths(data.input)
                              : inputPathsFromText(entry.name, data.text);
                            if (paths.length) {
                              entry.paths = unique(entry.paths.concat(paths));
                              await post("before_write", { paths: entry.paths, sessionID: await rootSessionId(ctx, id) });
                            }
                            continue;
                          }
                          if (type === "session.tool.success" || type === "session.tool.failed") {
                            const data = dataValue(raw);
                            const entry = toolCalls.get(data.id);
                            if (entry && isWriteToolName(entry.name) && entry.paths.length) {
                              await post("file_changed", { paths: entry.paths, sessionID: await rootSessionId(ctx, id) });
                            }
                            continue;
                          }
                          if (session.parentID) {
                            if (lifecycle) await debug("ignored child lifecycle id=" + id + " parent=" + session.parentID);
                            continue;
                          }
                          if (type === "session.execution.started") {
                            await post("turn_start", { sessionID: id });
                          } else if (type === "session.execution.succeeded" || type === "session.execution.interrupted") {
                            await post("turn_end", { sessionID: id });
                          } else if (type === "session.execution.failed") {
                            await post("turn_end_failed", { sessionID: id });
                          } else if (type === "session.idle") {
                            await post("turn_end", { sessionID: id });
                          } else if (type === "session.status") {
                            const status = statusValue(raw);
                            if (["busy", "running", "working", "active"].includes(status)) {
                              await post("turn_start", { sessionID: id });
                            } else if (["idle", "completed", "complete", "error", "failed"].includes(status)) {
                              await post("turn_end", { sessionID: id });
                            }
                          }
                        } catch (error) {
                          await debug("event handler error: " + (error && error.message ? error.message : String(error)));
                        }
                      }
                    } catch (error) {
                      await debug("event stream ended: " + (error && error.message ? error.message : String(error)));
                    }
                  })();
                }
              }
            };
        """.trimIndent()
        Files.writeString(pluginFile, js)
        log.info("Written OpenCode V2 JS plugin to $pluginFile")
    }

    private fun writeLauncherScripts(toolsDir: Path, tabId: String, token: String, port: Int): AiTerminalLauncher.Paths {
        val settings = AiTerminalToolsSettings.getInstance().getState()
        return AiTerminalLauncher.write(
            toolsDir = toolsDir,
            toolName = "opencode2",
            tabId = tabId,
            token = token,
            port = port,
            customCommand = settings.openCode2TerminalCommand,
            defaultCommand = if (SystemInfo.isWindows) "opencode2.cmd" else "opencode2"
        )
    }

    fun cleanupLauncherScripts(basePath: Path, tabId: String) {
        AiTerminalLauncher.cleanup(
            basePath.resolve(".idea").resolve("ai-terminal-tools"), "opencode2", tabId
        )
    }
}
