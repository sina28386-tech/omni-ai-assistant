import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { promises as fs } from "node:fs";
import { randomUUID } from "node:crypto";
import os from "node:os";
import path from "node:path";

const dir = path.join(os.tmpdir(), `omni-provider-test-${randomUUID()}`);
process.env.OMNI_DATA_DIR = dir;

const {
  chatEndpoint,
  createProvider,
  deleteProvider,
  getProvider,
  getProviderKey,
  listProviders,
  modelsEndpoint,
  publicProviders,
  updateProvider,
  validateBaseUrl,
} = await import("../server/providers.js");

beforeAll(async () => {
  await fs.rm(dir, { recursive: true, force: true });
});

afterAll(async () => {
  await fs.rm(dir, { recursive: true, force: true });
});

describe("provider URL normalization", () => {
  it("accepts https and adds a missing scheme", () => {
    expect(validateBaseUrl("api.example.com/v1/")).toBe("https://api.example.com/v1");
    expect(validateBaseUrl("https://api.example.com/v1")).toBe("https://api.example.com/v1");
  });

  it("allows plain HTTP only for private hosts", () => {
    expect(validateBaseUrl("http://127.0.0.1:8787/v1")).toBe("http://127.0.0.1:8787/v1");
    expect(validateBaseUrl("http://192.168.1.8:1234/v1")).toBe("http://192.168.1.8:1234/v1");
    expect(() => validateBaseUrl("http://api.example.com/v1")).toThrow(/Plain HTTP/);
  });

  it("rejects credentials and unsupported schemes", () => {
    expect(() => validateBaseUrl("https://user:secret@example.com/v1")).toThrow(/credentials/);
    expect(() => validateBaseUrl("ftp://example.com/v1")).toThrow(/http and https/);
    expect(() => validateBaseUrl("")).toThrow(/required/);
  });

  it("builds both endpoints from a versioned base URL", () => {
    expect(chatEndpoint("http://127.0.0.1:8787/v1")).toBe("http://127.0.0.1:8787/v1/chat/completions");
    expect(modelsEndpoint("http://127.0.0.1:8787/v1")).toBe("http://127.0.0.1:8787/v1/models");
    expect(chatEndpoint("https://host/v1/chat/completions")).toBe("https://host/v1/chat/completions");
    expect(modelsEndpoint("https://host/v1/chat/completions")).toBe("https://host/v1/models");
  });
});

describe("provider lifecycle", () => {
  it("stores a key separately and never returns it", async () => {
    const created = await createProvider({
      name: "Test gateway",
      baseUrl: "https://example.com/v1",
      model: "test-model",
      apiKey: "test-secret-key",
    });
    expect(created.hasKey).toBe(true);
    expect(created.keyHint).toBe("saved");
    expect("apiKey" in created).toBe(false);
    expect(JSON.stringify(created)).not.toContain("test-secret-key");

    const stored = await getProviderKey(created.id);
    expect(stored).toBe("test-secret-key");

    const rows = await publicProviders();
    expect(rows).toHaveLength(1);
    expect(rows[0].name).toBe("Test gateway");
    expect(JSON.stringify(rows)).not.toContain("test-secret-key");
  });

  it("updates fields and can remove the key explicitly", async () => {
    const [first] = await listProviders();
    const updated = await updateProvider(first.id, {
      name: "Renamed",
      temperature: 9,
      maxTokens: 1,
      apiKey: "",
    });
    expect(updated?.name).toBe("Renamed");
    expect(updated?.temperature).toBe(2);
    expect(updated?.maxTokens).toBe(64);
    expect(updated?.hasKey).toBe(false);
    expect(await getProviderKey(first.id)).toBe("");
    expect(await getProvider(first.id)).not.toBeNull();
  });

  it("deletes the provider and its key", async () => {
    const [first] = await listProviders();
    expect(await deleteProvider(first.id)).toBe(true);
    expect(await deleteProvider(first.id)).toBe(false);
    expect(await listProviders()).toHaveLength(0);
  });
});
