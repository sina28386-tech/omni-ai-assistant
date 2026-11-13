import { promises as fs } from "node:fs";
import path from "node:path";
import { createHash, randomUUID } from "node:crypto";
import { dataDir, readJson, writeJson } from "./storage.js";
import { HttpError } from "./errors.js";

export interface Workspace { id: string; name: string; rootPath: string; createdAt: number }
export interface WorkspaceFile { path: string; content: string; sha256: string }
const catalog = path.join(dataDir, "workspaces.json");
const MAX_BYTES = 200_000;
const locks = new Map<string, Promise<unknown>>();
const blocked = new Set([".git", "node_modules", ".ssh", ".aws", ".azure", ".grokbot", ".codex", ".venv", "venv", "__pycache__", ".gradle", "build", "dist", ".ds_store", "secrets.json", "credentials.json", "debug.keystore"]);

function allowedName(name: string): boolean {
  const lower = name.toLowerCase();
  return !blocked.has(lower) && !lower.startsWith(".env") && !/\.(?:pem|key|p12|pfx|keystore)$/i.test(name);
}

function relativePath(input: string, allowEmpty = false): string {
  if (typeof input !== "string" || input.includes("\0") || input.includes(":") || path.isAbsolute(input) || path.win32.isAbsolute(input)) {
    throw new HttpError(400, "workspace_path_invalid", "Choose a relative project path.");
  }
  const parts = input.replace(/\\/g, "/").split("/");
  if ((!input && !allowEmpty) || parts.some(part => part === ".." || part === "." || (part && (!allowedName(part) || /[. ]$/.test(part))))) {
    throw new HttpError(400, "workspace_path_invalid", "This project path is not available.");
  }
  return parts.filter(Boolean).join("/");
}

export async function listWorkspaces(): Promise<Workspace[]> {
  return readJson<Workspace[]>(catalog, []);
}

export async function registerWorkspace(input: string): Promise<Workspace> {
  if (typeof input !== "string" || !path.isAbsolute(input) || input.includes("\0")) throw new HttpError(400, "workspace_required", "Enter an absolute project folder path.");
  let root: string;
  try {
    root = await fs.realpath(input.trim());
    if (!(await fs.stat(root)).isDirectory() || path.parse(root).root === root) throw new Error();
  } catch { throw new HttpError(400, "workspace_unavailable", "The project folder is not available."); }
  return locked("catalog", async () => {
    const all = await listWorkspaces();
    const found = all.find(item => item.rootPath.toLowerCase() === root.toLowerCase());
    if (found) return found;
    const workspace = { id: randomUUID(), name: path.basename(root), rootPath: root, createdAt: Date.now() };
    await writeJson(catalog, [...all, workspace]);
    return workspace;
  });
}

export async function getWorkspace(id: string): Promise<Workspace> {
  const workspace = (await listWorkspaces()).find(item => item.id === id);
  if (!workspace) throw new HttpError(404, "workspace_not_found", "Select a project first.");
  return workspace;
}

async function resolveProjectPath(id: string, input: string, allowMissing = false): Promise<{ absolute: string; relative: string }> {
  const workspace = await getWorkspace(id);
  const relative = relativePath(input, true);
  const root = await fs.realpath(workspace.rootPath);
  if (root.toLowerCase() !== workspace.rootPath.toLowerCase()) throw new HttpError(409, "workspace_changed", "The selected project folder changed. Select it again.");
  let absolute = root;
  const parts = relative.split("/").filter(Boolean);
  for (let i = 0; i < parts.length; i++) {
    absolute = path.join(absolute, parts[i]);
    try {
      const stat = await fs.lstat(absolute);
      if (stat.isSymbolicLink()) throw new HttpError(400, "workspace_link_blocked", "Linked paths are not available in the project workspace.");
      if (i < parts.length - 1 && !stat.isDirectory()) throw new HttpError(400, "workspace_path_invalid", "The parent path is not a folder.");
    } catch (error) {
      if (allowMissing && i === parts.length - 1 && (error as NodeJS.ErrnoException).code === "ENOENT") break;
      if (error instanceof HttpError) throw error;
      throw new HttpError(404, "workspace_file_not_found", "The project file was not found.");
    }
  }
  const contained = path.relative(root, absolute);
  if (contained.startsWith("..") || path.isAbsolute(contained)) throw new HttpError(400, "workspace_path_invalid", "Choose a path inside the project.");
  return { absolute, relative };
}

export async function listWorkspaceDirectory(id: string, directory = "") {
  const target = await resolveProjectPath(id, directory);
  let entries;
  try { entries = await fs.readdir(target.absolute, { withFileTypes: true }); }
  catch { throw new HttpError(400, "workspace_directory_invalid", "This path is not a readable folder."); }
  const visible = entries.filter(entry => allowedName(entry.name) && !entry.isSymbolicLink() && (entry.isFile() || entry.isDirectory()))
    .sort((a, b) => Number(b.isDirectory()) - Number(a.isDirectory()) || a.name.localeCompare(b.name));
  return {
    entries: visible.slice(0, 200).map(entry => ({ name: entry.name, path: [target.relative, entry.name].filter(Boolean).join("/"), kind: entry.isDirectory() ? "directory" as const : "file" as const })),
    truncated: visible.length > 200,
  };
}

async function readText(absolute: string): Promise<{ content: string; sha256: string }> {
  const stat = await fs.stat(absolute);
  if (!stat.isFile() || stat.size > MAX_BYTES) throw new HttpError(400, "workspace_file_limit", "Choose a text file smaller than 200 KB.");
  const buffer = await fs.readFile(absolute);
  if (buffer.length > MAX_BYTES || buffer.includes(0)) throw new HttpError(400, "workspace_binary_file", "Only text files are supported.");
  let content: string;
  try { content = new TextDecoder("utf-8", { fatal: true }).decode(buffer); }
  catch { throw new HttpError(400, "workspace_binary_file", "Choose a UTF-8 text file."); }
  return { content, sha256: createHash("sha256").update(buffer).digest("hex") };
}

export async function readWorkspaceFile(id: string, input: string): Promise<WorkspaceFile> {
  relativePath(input);
  const target = await resolveProjectPath(id, input);
  return { path: target.relative, ...await readText(target.absolute) };
}

export async function writeWorkspaceFile(id: string, input: string, content: string, expectedSha: string | null, approved: boolean, signal?: AbortSignal): Promise<WorkspaceFile> {
  relativePath(input);
  if (approved !== true) throw new HttpError(400, "workspace_approval_required", "Review and approve the file change first.");
  if (typeof content !== "string" || Buffer.byteLength(content, "utf8") > MAX_BYTES || content.includes("\0")) throw new HttpError(400, "workspace_file_limit", "The change exceeds the text file limit.");
  if (expectedSha !== null && (typeof expectedSha !== "string" || !/^[a-f0-9]{64}$/.test(expectedSha))) throw new HttpError(400, "workspace_digest_required", "Reload the file before saving.");
  return locked(id, async () => {
    signal?.throwIfAborted();
    const target = await resolveProjectPath(id, input, true);
    let original: { sha256: string } | null = null;
    try { original = await readText(target.absolute); }
    catch (error) { if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error; }
    if ((original?.sha256 ?? null) !== expectedSha) throw new HttpError(409, "workspace_conflict", "This file changed. Reload it and review the change again.");
    signal?.throwIfAborted();
    if (!original) {
      try { await fs.writeFile(target.absolute, content, { encoding: "utf8", flag: "wx" }); }
      catch (error) { if ((error as NodeJS.ErrnoException).code === "EEXIST") throw new HttpError(409, "workspace_conflict", "This file was created elsewhere. Reload it."); throw error; }
    } else {
      const temporary = path.join(path.dirname(target.absolute), `.omni-${randomUUID()}.tmp`);
      try {
        await fs.writeFile(temporary, content, { encoding: "utf8", flag: "wx" });
        await resolveProjectPath(id, input);
        const current = await readText(target.absolute);
        if (current.sha256 !== expectedSha) throw new HttpError(409, "workspace_conflict", "This file changed. Reload it.");
        signal?.throwIfAborted();
        await fs.rename(temporary, target.absolute);
      } finally { await fs.unlink(temporary).catch(() => undefined); }
    }
    return { path: target.relative, ...await readText(target.absolute) };
  });
}

async function locked<T>(id: string, operation: () => Promise<T>): Promise<T> {
  const previous = locks.get(id) ?? Promise.resolve();
  const current = previous.catch(() => undefined).then(operation);
  locks.set(id, current);
  try { return await current; }
  finally { if (locks.get(id) === current) locks.delete(id); }
}
