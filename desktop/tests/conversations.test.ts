import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { promises as fs } from "node:fs";
import { randomUUID } from "node:crypto";
import os from "node:os";
import path from "node:path";

const dir = path.join(os.tmpdir(), `omni-conversation-test-${randomUUID()}`);
process.env.OMNI_DATA_DIR = dir;

const {
  appendMessage,
  createConversation,
  deleteConversation,
  deriveTitle,
  getConversation,
  listConversations,
  pinConversation,
  renameConversation,
} = await import("../server/conversations.js");

beforeAll(async () => {
  await fs.rm(dir, { recursive: true, force: true });
});

beforeEach(async () => {
  await fs.rm(path.join(dir, "conversations"), { recursive: true, force: true });
});

afterAll(async () => {
  await fs.rm(dir, { recursive: true, force: true });
});

describe("title derivation", () => {
  it("uses the first sentence in Latin or Persian text", () => {
    expect(deriveTitle("Plan my week. Then add detail.")).toBe("Plan my week");
    expect(deriveTitle("برنامه هفته‌ام را بچین؟ بعد جزئیات بده.")).toBe("برنامه هفته‌ام را بچین");
    expect(deriveTitle("   ")).toBe("");
  });

  it("truncates very long first sentences", () => {
    const title = deriveTitle(`${"a".repeat(90)}. next`);
    expect(title.length).toBeLessThanOrEqual(64);
    expect(title.endsWith("…")).toBe(true);
  });
});

describe("conversation persistence", () => {
  it("creates, appends, summarizes, renames, pins, and deletes", async () => {
    const created = await createConversation();
    expect(created.messages).toEqual([]);
    const withUser = await appendMessage(created.id, {
      id: randomUUID(),
      role: "user",
      content: "سلام! یک پرسش کوتاه.",
      createdAt: Date.now(),
    });
    expect(withUser.title).toBe("سلام");
    expect(withUser.messages).toHaveLength(1);

    await appendMessage(created.id, {
      id: randomUUID(),
      role: "assistant",
      content: "پاسخ کوتاه.",
      createdAt: Date.now(),
      status: "complete",
    });
    const summaries = await listConversations();
    expect(summaries).toHaveLength(1);
    expect(summaries[0].messageCount).toBe(2);
    expect(summaries[0].preview).toBe("پاسخ کوتاه.");

    const renamed = await renameConversation(created.id, "  A  better   title  ");
    expect(renamed.title).toBe("A better title");
    const pinned = await pinConversation(created.id, true);
    expect(pinned.pinned).toBe(true);

    expect(await deleteConversation(created.id)).toBe(true);
    expect(await getConversation(created.id)).toBeNull();
    expect(await listConversations()).toHaveLength(0);
  });

  it("rejects invalid ids before touching the filesystem", async () => {
    await expect(getConversation("../../secrets")).rejects.toMatchObject({ status: 400 });
  });
});
