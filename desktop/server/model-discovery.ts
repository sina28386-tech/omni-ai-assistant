import { HttpError } from "./errors.js";
import { modelsEndpoint, validateBaseUrl } from "./providers.js";

/** Reads the selected provider's actual catalog; it does not save the supplied key. */
export async function discoverModels(baseUrl: string, apiKey: string): Promise<{ models: string[] }> {
  const endpoint = modelsEndpoint(validateBaseUrl(baseUrl));
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 20_000);
  try {
    const anthropic = new URL(endpoint).hostname === "api.anthropic.com";
    const models = new Set<string>();
    let cursor = "";
    for (let page = 0; page < (anthropic ? 20 : 1); page++) {
      const url = new URL(endpoint);
      if (cursor) url.searchParams.set("after_id", cursor);
      const response = await fetch(url.toString(), {
        headers: { ...(apiKey.trim() ? { Authorization: `Bearer ${apiKey.trim()}` } : {}), ...(anthropic ? { "anthropic-version": "2023-06-01" } : {}) },
        redirect: "error", signal: controller.signal,
      });
      if (!response.ok) throw new HttpError(502, "models_unavailable", `The provider returned ${response.status} for its model list.`);
      const payload = await response.json() as { data?: unknown; has_more?: boolean; last_id?: unknown };
      if (!Array.isArray(payload.data)) throw new Error("Invalid provider response");
      for (const row of payload.data) if (row && typeof row === "object") {
        const id = (row as { id?: unknown }).id;
        if (typeof id === "string" && id.length > 0 && id.length <= 160) models.add(id);
      }
      if (!anthropic || !payload.has_more) break;
      if (typeof payload.last_id !== "string" || !payload.last_id || payload.last_id === cursor || page === 19) throw new Error("Model catalog pagination failed");
      cursor = payload.last_id;
    }
    return { models: [...models].sort((a, b) => a.localeCompare(b)) };
  } catch (error) {
    if (error instanceof HttpError) throw error;
    throw new HttpError(502, "models_unavailable", "Could not read the provider's model list. Enter the model id manually.");
  } finally { clearTimeout(timer); }
}
