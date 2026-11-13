export type Language = "fa" | "en";
export type ThemeMode = "system" | "dark" | "light";
export type Density = "comfortable" | "compact";
export type ExperienceMode = "simple" | "studio";

export interface Settings {
  language: Language;
  theme: ThemeMode;
  density: Density;
  mode: ExperienceMode;
  sendOnEnter: boolean;
  autoLowRisk: boolean;
  showInspector: boolean;
  systemPrompt: string;
  personality: "default" | "concise" | "teacher" | "engineer";
  activeProviderId: string;
  createdAt: number;
  updatedAt: number;
}

export interface ProviderRecord {
  kind?: "api" | "codex";
  id: string;
  name: string;
  baseUrl: string;
  model: string;
  enabled: boolean;
  temperature: number;
  maxTokens: number;
  createdAt: number;
  updatedAt: number;
}

export interface PublicProvider extends ProviderRecord {
  hasKey: boolean;
  keyHint: string;
}

export interface ChatMessage {
  runId?: string;
  id: string;
  role: "user" | "assistant" | "system";
  content: string;
  createdAt: number;
  providerId?: string;
  model?: string;
  status?: "complete" | "stopped" | "error";
  promptTokens?: number;
  completionTokens?: number;
  usedMemory?: string[];
}

export interface ConversationSummary {
  id: string;
  title: string;
  createdAt: number;
  updatedAt: number;
  pinned: boolean;
  messageCount: number;
  preview: string;
  providerId?: string;
  model?: string;
}

export interface Conversation {
  id: string;
  title: string;
  createdAt: number;
  updatedAt: number;
  pinned: boolean;
  messages: ChatMessage[];
  providerId?: string;
  model?: string;
}

export interface MemoryFact {
  id: string;
  key: string;
  value: string;
  source: string;
  createdAt: number;
  updatedAt: number;
}

export interface UsageEvent {
  id: string;
  conversationId: string;
  providerId: string;
  model: string;
  promptTokens: number;
  completionTokens: number;
  createdAt: number;
}

export interface UsageSummary {
  requests: number;
  promptTokens: number;
  completionTokens: number;
  byDay: Array<{ day: string; requests: number; promptTokens: number; completionTokens: number }>;
}

export interface ApiErrorBody {
  error: string;
  code: string;
  detail?: string;
}
