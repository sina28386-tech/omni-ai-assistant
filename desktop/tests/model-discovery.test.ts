import { afterEach, describe, expect, it, vi } from "vitest";
import { discoverModels } from "../server/model-discovery.js";

afterEach(() => vi.restoreAllMocks());
describe("model discovery", () => {
  it("reads the complete Claude catalog with the required version header", async () => {
    const mock = vi.spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(new Response(JSON.stringify({ data: [{ id: "claude-a" }], has_more: true, last_id: "claude-a" })))
      .mockResolvedValueOnce(new Response(JSON.stringify({ data: [{ id: "claude-b" }], has_more: false })));
    expect(await discoverModels("https://api.anthropic.com/v1", "fixture-key")).toEqual({ models: ["claude-a", "claude-b"] });
    expect(mock).toHaveBeenNthCalledWith(1, "https://api.anthropic.com/v1/models", expect.objectContaining({ headers: { Authorization: "Bearer fixture-key", "anthropic-version": "2023-06-01" } }));
    expect(mock).toHaveBeenNthCalledWith(2, "https://api.anthropic.com/v1/models?after_id=claude-a", expect.anything());
  });
  it("uses the selected endpoint and key without persisting either, and returns real model IDs", async () => {
    const mock = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({ data: [{ id: "model-b" }, { id: "model-a" }, { id: "model-a" }, {}] })));
    expect(await discoverModels("https://api.example.com/v1", " key ")).toEqual({ models: ["model-a", "model-b"] });
    expect(mock).toHaveBeenCalledWith("https://api.example.com/v1/models", expect.objectContaining({ headers: { Authorization: "Bearer key" }, redirect: "error" }));
  });
  it("fails clearly on a bad catalog without exposing a key", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response("{}"));
    await expect(discoverModels("https://api.example.com/v1", "test-secret")).rejects.toMatchObject({ code: "models_unavailable" });
  });
  it("rejects a URL containing embedded credentials before making a request", async () => {
    const mock = vi.spyOn(globalThis, "fetch");
    await expect(discoverModels("https://user:password@example.com/v1", "")).rejects.toMatchObject({ code: "provider_url_credentials" });
    expect(mock).not.toHaveBeenCalled();
  });
});
