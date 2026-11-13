import { promises as fs } from "node:fs";
import path from "node:path";
import os from "node:os";
import { randomUUID } from "node:crypto";

const APP_DIR_NAME = "omni-desktop";

export function resolveDataDir(): string {
  const override = process.env.OMNI_DATA_DIR?.trim();
  if (override) return path.resolve(override);
  if (process.platform === "win32" && process.env.APPDATA) {
    return path.join(process.env.APPDATA, APP_DIR_NAME);
  }
  return path.join(os.homedir(), ".config", APP_DIR_NAME);
}

export const dataDir = resolveDataDir();
export const conversationsDir = path.join(dataDir, "conversations");
const writes = new Map<string, Promise<unknown>>();

export async function ensureDataDirs(): Promise<void> {
  await fs.mkdir(conversationsDir, { recursive: true, mode: 0o700 });
}

export async function readJson<T>(file: string, fallback: T): Promise<T> {
  try {
    const raw = await fs.readFile(file, "utf8");
    return JSON.parse(raw) as T;
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return fallback;
    throw error;
  }
}

export async function writeJson(file: string, value: unknown): Promise<void> {
  // Serialize writers to one record and never reuse a timestamp-based temp
  // name. Task progress/cancel updates can otherwise rename each other's file.
  const previous = writes.get(file) ?? Promise.resolve();
  const next = previous.catch(() => undefined).then(() => writeJsonRecord(file, value));
  writes.set(file, next);
  try { await next; }
  finally { if (writes.get(file) === next) writes.delete(file); }
}

async function writeJsonRecord(file: string, value: unknown): Promise<void> {
  const dir = path.dirname(file);
  await fs.mkdir(dir, { recursive: true, mode: 0o700 });
  const tmp = `${file}.${process.pid}.${randomUUID()}.tmp`;
  try {
    await fs.writeFile(tmp, JSON.stringify(value, null, 2), { encoding: "utf8", mode: 0o600, flag: "wx" });
    await fs.rename(tmp, file);
  } finally { await fs.unlink(tmp).catch(() => undefined); }
  try {
    await fs.chmod(file, 0o600);
  } catch {
    // Windows does not implement POSIX modes; the file stays inside the user profile.
  }
}

export async function removeFile(file: string): Promise<void> {
  try {
    await fs.unlink(file);
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error;
  }
}

export async function listJsonFiles(dir: string): Promise<string[]> {
  try {
    const entries = await fs.readdir(dir, { withFileTypes: true });
    return entries.filter((e) => e.isFile() && e.name.endsWith(".json")).map((e) => path.join(dir, e.name));
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return [];
    throw error;
  }
}
