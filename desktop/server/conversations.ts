import path from "node:path";
import { randomUUID } from "node:crypto";
import { conversationsDir, listJsonFiles, readJson, removeFile, writeJson } from "./storage.js";
import type { ChatMessage, Conversation, ConversationSummary } from "./types.js";
import { HttpError } from "./errors.js";

const locks = new Map<string, Promise<unknown>>();

export function isConversationId(id: string): boolean {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(id);
}

export function conversationFile(id: string): string {
  if (!isConversationId(id)) throw new HttpError(400, "conversation_id_invalid", "Invalid conversation id.");
  return path.join(conversationsDir, `${id}.json`);
}

export async function listConversations(): Promise<ConversationSummary[]> {
  const files = await listJsonFiles(conversationsDir);
  const rows = await Promise.all(
    files.map(async (file) => {
      try {
        return await readJson<Conversation | null>(file, null);
      } catch {
        return null;
      }
    }),
  );
  return rows
    .filter((row): row is Conversation => Boolean(row && isConversationId(row.id) && Array.isArray(row.messages)))
    .map(toSummary)
    .sort((a, b) => b.updatedAt - a.updatedAt);
}

export async function getConversation(id: string): Promise<Conversation | null> {
  return readJson<Conversation | null>(conversationFile(id), null);
}

export async function createConversation(title = ""): Promise<Conversation> {
  const now = Date.now();
  const conversation: Conversation = {
    id: randomUUID(),
    title: title.trim().slice(0, 120),
    createdAt: now,
    updatedAt: now,
    pinned: false,
    messages: [],
  };
  await writeJson(conversationFile(conversation.id), conversation);
  return conversation;
}

export async function renameConversation(id: string, title: string): Promise<Conversation> {
  return mutateConversation(id, (conversation) => {
    conversation.title = title.replace(/\s+/g, " ").trim().slice(0, 120);
    return conversation;
  });
}

export async function pinConversation(id: string, pinned: boolean): Promise<Conversation> {
  return mutateConversation(id, (conversation) => {
    conversation.pinned = Boolean(pinned);
    return conversation;
  });
}

export async function deleteConversation(id: string): Promise<boolean> {
  const file = conversationFile(id);
  const existing = await getConversation(id);
  if (!existing) return false;
  await removeFile(file);
  return true;
}

export async function appendMessage(id: string, message: ChatMessage): Promise<Conversation> {
  return mutateConversation(id, (conversation) => {
    conversation.messages.push(message);
    if (!conversation.title && message.role === "user") {
      conversation.title = deriveTitle(message.content);
    }
    conversation.updatedAt = Date.now();
    return conversation;
  });
}

export async function updateMessage(
  id: string,
  messageId: string,
  patch: Partial<ChatMessage>,
): Promise<Conversation> {
  return mutateConversation(id, (conversation) => {
    const message = conversation.messages.find((item) => item.id === messageId);
    if (!message) throw new HttpError(404, "message_not_found", "Message not found.");
    Object.assign(message, patch);
    conversation.updatedAt = Date.now();
    return conversation;
  });
}

export async function mutateConversation(
  id: string,
  mutate: (conversation: Conversation) => Conversation,
): Promise<Conversation> {
  const file = conversationFile(id);
  const previous = locks.get(id) ?? Promise.resolve();
  const next = previous
    .catch(() => undefined)
    .then(async () => {
      const conversation = await readJson<Conversation | null>(file, null);
      if (!conversation) throw new HttpError(404, "conversation_not_found", "Conversation not found.");
      const updated = mutate(conversation);
      await writeJson(file, updated);
      return updated;
    });
  locks.set(id, next);
  try {
    return await next;
  } finally {
    if (locks.get(id) === next) locks.delete(id);
  }
}

export function deriveTitle(text: string): string {
  const clean = text.replace(/\s+/g, " ").trim();
  if (!clean) return "";
  const firstLine = clean.split(/[.!?۔؟\n]/)[0]?.trim() || clean;
  return firstLine.length > 64 ? `${firstLine.slice(0, 61)}…` : firstLine;
}

export function toSummary(conversation: Conversation): ConversationSummary {
  const last = [...conversation.messages].reverse().find((message) => message.content.trim());
  return {
    id: conversation.id,
    title: conversation.title,
    createdAt: conversation.createdAt,
    updatedAt: conversation.updatedAt,
    pinned: Boolean(conversation.pinned),
    messageCount: conversation.messages.length,
    preview: last?.content.replace(/\s+/g, " ").trim().slice(0, 140) ?? "",
    providerId: conversation.providerId,
    model: conversation.model,
  };
}
