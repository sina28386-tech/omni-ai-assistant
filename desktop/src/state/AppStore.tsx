import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { api, request } from "../lib/api";
import { studioApi, type BotRun } from "../lib/studioApi";
import { translate, type MessageKey } from "../lib/i18n";
import type {
  ChatMessage,
  ChatMeta,
  Conversation,
  ConversationSummary,
  MemoryFact,
  ProviderProfile,
  Screen,
  Settings,
  UsageSummary,
} from "../lib/types";

type Boot = "loading" | "ready" | "error";
type ToastKind = "info" | "error";

interface AppState {
  boot: Boot;
  bootError: string;
  settings: Settings | null;
  providers: ProviderProfile[];
  conversations: ConversationSummary[];
  active: Conversation | null;
  activeId: string | null;
  drafts: Record<string, string>;
  memory: MemoryFact[];
  usage: UsageSummary | null;
  screen: Screen;
  streaming: boolean;
  streamText: string;
  streamError: string | null;
  streamMeta: ChatMeta | null;
  chatRun: BotRun | null;
  sidebarOpen: boolean;
  inspectorOpen: boolean;
  paletteOpen: boolean;
  toast: { message: string; kind: ToastKind; nonce: number } | null;
}

interface AppActions {
  t: (key: MessageKey, vars?: Record<string, string | number>) => string;
  uiLanguage: "fa" | "en";
  resolvedTheme: "light" | "dark";
  activeProvider: ProviderProfile | null;
  setScreen: (screen: Screen) => void;
  newChat: () => void;
  setDraft: (key: string, text: string) => void;
  setNavigationGuard: (guard: ((proceed: () => void) => void) | null) => void;
  openConversation: (id: string) => Promise<void>;
  send: (content: string) => Promise<void>;
  stop: () => void;
  approveComputerCommand: (approve: boolean) => Promise<void>;
  retry: () => Promise<void>;
  copyText: (text: string) => Promise<void>;
  renameConversation: (id: string, title: string) => Promise<void>;
  deleteConversation: (id: string) => Promise<void>;
  pinConversation: (id: string, pinned: boolean) => Promise<void>;
  updateSettings: (patch: Partial<Settings>) => Promise<void>;
  selectProvider: (id: string) => Promise<void>;
  saveProvider: (input: Record<string, unknown>, id?: string) => Promise<ProviderProfile>;
  deleteProvider: (id: string) => Promise<void>;
  testProvider: (id: string) => Promise<{ latencyMs: number; reply: string }>;
  fetchModels: (id: string) => Promise<string[]>;
  saveMemory: (input: { key: string; value: string; source?: string }, id?: string) => Promise<MemoryFact>;
  deleteMemory: (id: string) => Promise<void>;
  clearMemory: () => Promise<void>;
  showToast: (message: string, kind?: ToastKind) => void;
  toggleTheme: () => void;
  toggleLanguage: () => void;
  setSidebarOpen: (open: boolean) => void;
  setInspectorOpen: (open: boolean) => void;
  setPaletteOpen: (open: boolean) => void;
  refreshConversations: () => Promise<void>;
  refreshUsage: () => Promise<void>;
  reload: () => Promise<void>;
}

const AppContext = createContext<
  | (AppState & {
      actions: AppActions;
      uiLanguage: "fa" | "en";
      resolvedTheme: "light" | "dark";
      activeProvider: ProviderProfile | null;
    })
  | null
>(null);

const initialState: AppState = {
  boot: "loading",
  bootError: "",
  settings: null,
  providers: [],
  conversations: [],
  active: null,
  activeId: null,
  drafts: {},
  memory: [],
  usage: null,
  screen: "assistant",
  streaming: false,
  streamText: "",
  streamError: null,
  streamMeta: null,
  chatRun: null,
  sidebarOpen: false,
  inspectorOpen: false,
  paletteOpen: false,
  toast: null,
};

export function AppProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AppState>(initialState);
  const [systemDark, setSystemDark] = useState(() =>
    typeof window !== "undefined" ? window.matchMedia("(prefers-color-scheme: dark)").matches : true,
  );
  const stateRef = useRef(state);
  stateRef.current = state;
  const abortRef = useRef<AbortController | null>(null);
  const chatRunRef = useRef<string | null>(null);
  const startingChatRef = useRef(false);
  const toastTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const navigationGuard = useRef<((proceed: () => void) => void) | null>(null);

  const uiLanguage = state.settings?.language ?? "fa";
  const resolvedTheme: "light" | "dark" =
    state.settings?.theme === "light" ? "light" : state.settings?.theme === "dark" ? "dark" : systemDark ? "dark" : "light";

  const t = useCallback(
    (key: MessageKey, vars?: Record<string, string | number>) => translate(uiLanguage, key, vars),
    [uiLanguage],
  );

  const showToast = useCallback((message: string, kind: ToastKind = "info") => {
    if (toastTimer.current) clearTimeout(toastTimer.current);
    setState((current) => ({ ...current, toast: { message, kind, nonce: Date.now() } }));
    toastTimer.current = setTimeout(() => {
      setState((current) => ({ ...current, toast: null }));
    }, 3600);
  }, []);

  const refreshConversations = useCallback(async () => {
    const conversations = await api.listConversations();
    setState((current) => ({ ...current, conversations }));
  }, []);

  const refreshUsage = useCallback(async () => {
    const usage = await api.usage();
    setState((current) => ({ ...current, usage }));
  }, []);

  const load = useCallback(async () => {
    setState((current) => ({ ...current, boot: "loading", bootError: "" }));
    try {
      await api.ensureToken();
      const [settings, providers, conversations, memory, usage] = await Promise.all([
        api.getSettings(),
        api.listProviders(),
        api.listConversations(),
        api.listMemory(),
        api.usage(),
      ]);
      let nextSettings = settings;
      if (!nextSettings.activeProviderId && providers.length) {
        const first = providers.find((provider) => provider.enabled) ?? providers[0];
        try {
          nextSettings = await api.updateSettings({ activeProviderId: first.id });
        } catch {
          // A failed preference write must not block the app.
        }
      }
      setState((current) => ({
        ...current,
        boot: "ready",
        settings: nextSettings,
        providers,
        conversations,
        memory,
        usage,
        inspectorOpen: false,
        bootError: "",
      }));
    } catch (error) {
      setState((current) => ({
        ...current,
        boot: "error",
        bootError: error instanceof Error ? error.message : "The local service is offline.",
      }));
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    const media = window.matchMedia("(prefers-color-scheme: dark)");
    const onChange = () => setSystemDark(media.matches);
    media.addEventListener("change", onChange);
    return () => media.removeEventListener("change", onChange);
  }, []);

  useEffect(() => {
    const root = document.documentElement;
    root.dataset.theme = resolvedTheme;
    root.dataset.density = state.settings?.density ?? "comfortable";
    root.lang = uiLanguage;
    root.dir = uiLanguage === "fa" ? "rtl" : "ltr";
    root.style.setProperty("--omni-accent", resolvedTheme === "dark" ? "#ff6b45" : "#c63a17");
    const meta = document.querySelector('meta[name="theme-color"]');
    if (meta) meta.setAttribute("content", resolvedTheme === "dark" ? "#0a0908" : "#f7f3ec");
    document.title = `Omni — ${translate(uiLanguage, "nav.assistant")}`;
  }, [resolvedTheme, uiLanguage, state.settings?.density]);

  const activeProvider = useMemo(() => {
    const byId = state.settings?.activeProviderId
      ? state.providers.find((provider) => provider.id === state.settings?.activeProviderId)
      : null;
    return byId ?? state.providers.find((provider) => provider.enabled) ?? state.providers[0] ?? null;
  }, [state.providers, state.settings?.activeProviderId]);

  const setScreen = useCallback((screen: Screen) => {
    const proceed = () => setState((current) => ({ ...current, screen, sidebarOpen: false }));
    if (navigationGuard.current && screen !== stateRef.current.screen) navigationGuard.current(proceed);
    else proceed();
  }, []);

  const newChat = useCallback(() => {
    if (stateRef.current.streaming || startingChatRef.current) return;
    const proceed = () => {
      abortRef.current?.abort();
      abortRef.current = null;
      setState((current) => ({
        ...current,
        active: null,
        activeId: null,
        screen: "assistant",
        streaming: false,
        streamText: "",
        streamError: null,
        streamMeta: null,
        chatRun: null,
        sidebarOpen: false,
      }));
    };
    if (navigationGuard.current) navigationGuard.current(proceed);
    else proceed();
  }, []);

  const openConversation = useCallback(
    async (id: string) => {
      if (stateRef.current.streaming) return;
      const proceed = async () => {
        try {
          const conversation = await api.getConversation(id);
          setState((current) => ({
            ...current,
            active: conversation,
            activeId: id,
            screen: "assistant",
            sidebarOpen: false,
            streamText: "",
            streamError: null,
            streamMeta: null,
            chatRun: null,
          }));
          const runs = await request<BotRun[]>(`/api/conversations/${encodeURIComponent(id)}/assistant-run`);
          const latest = runs[0];
          if (latest && stateRef.current.activeId === id) {
            setState(current => current.activeId === id ? { ...current, chatRun: latest } : current);
            if (["queued", "running", "waiting_approval"].includes(latest.state)) {
              startingChatRef.current = true;
              chatRunRef.current = latest.id;
              const controller = new AbortController();
              abortRef.current = controller;
              setState(current => current.activeId === id ? { ...current, streaming: true } : current);
              try {
                let run = latest;
                while (["queued", "running", "waiting_approval"].includes(run.state)) {
                  if (controller.signal.aborted) { await studioApi.cancelRun(run.id); break; }
                  await new Promise(resolve => setTimeout(resolve, 500));
                  run = await studioApi.getRun(run.id);
                  setState(current => current.activeId === id ? { ...current, chatRun: run } : current);
                }
                const fresh = await api.getConversation(id);
                setState(current => current.activeId === id ? { ...current, active: fresh, streaming: false, streamError: run.state === "failed" ? run.error ?? "Task failed" : null } : current);
              } finally {
                startingChatRef.current = false;
                if (chatRunRef.current === latest.id) chatRunRef.current = null;
                if (abortRef.current === controller) abortRef.current = null;
                setState(current => current.activeId === id ? { ...current, streaming: false } : current);
              }
            }
          }
        } catch (error) {
          showToast(error instanceof Error ? error.message : t("error.generic"), "error");
        }
      };
      if (navigationGuard.current) navigationGuard.current(() => void proceed());
      else await proceed();
    },
    [showToast, t],
  );

  const patchAssistant = useCallback((content: string, status: ChatMessage["status"], extra?: Partial<ChatMessage>) => {
    setState((current) => {
      if (!current.active) return current;
      const messages = current.active.messages.map((message) =>
        message.id === "local-assistant"
          ? { ...message, content, status, ...extra }
          : message,
      );
      return { ...current, active: { ...current.active, messages } };
    });
  }, []);

  const send = useCallback(async (content: string) => {
    const text = content.trim();
    if (!text || stateRef.current.streaming || startingChatRef.current) return;
    const provider = activeProvider;
    if (!provider?.enabled) { showToast(t("error.providerRequired"), "error"); return; }
    startingChatRef.current = true;
    let conversation = stateRef.current.active;
    let runId: string | null = null;
    let canceled = false;
    const controller = new AbortController();
    abortRef.current = controller;
    try {
      if (!conversation) conversation = await api.createConversation();
      const conversationId = conversation.id;
      const userMessage: ChatMessage = { id: "local-user", role: "user", content: text, createdAt: Date.now() };
      const assistantMessage: ChatMessage = { id: "local-assistant", role: "assistant", content: "", createdAt: Date.now(), providerId: provider.id, model: provider.model };
      setState(current => ({ ...current, active: { ...conversation!, messages: [...conversation!.messages, userMessage, assistantMessage] }, activeId: conversationId, streaming: true, streamText: "", streamError: null, streamMeta: null, chatRun: null, screen: "assistant", sidebarOpen: false }));
      if (controller.signal.aborted) throw new DOMException("Stopped", "AbortError");
      const run = await request<BotRun>(`/api/conversations/${encodeURIComponent(conversationId)}/assistant-run`, { method: "POST", body: JSON.stringify({ content: text, providerId: provider.id }) });
      runId = run.id;
      chatRunRef.current = run.id;
      if (controller.signal.aborted) { await studioApi.cancelRun(run.id); throw new DOMException("Stopped", "AbortError"); }
      for (;;) {
        if (controller.signal.aborted) { await studioApi.cancelRun(run.id); canceled = true; break; }
        const currentRun = await studioApi.getRun(run.id);
        setState(current => current.activeId === conversationId ? { ...current, chatRun: currentRun, ...(currentRun.output && current.active ? { active: { ...current.active, messages: current.active.messages.map(message => message.id === "local-assistant" ? { ...message, content: currentRun.output } : message) } } : {}) } : current);
        if (["complete", "failed", "canceled"].includes(currentRun.state)) {
          if (currentRun.state === "failed") throw new Error(currentRun.error || "The assistant could not complete the request.");
          canceled = currentRun.state === "canceled";
          break;
        }
        await new Promise(resolve => setTimeout(resolve, 500));
      }
      const fresh = await api.getConversation(conversationId);
      setState(current => current.activeId === conversationId ? { ...current, active: fresh, streaming: false, streamError: canceled ? null : current.streamError } : current);
      void refreshConversations(); void refreshUsage();
      void api.listMemory().then(memory => setState(current => ({ ...current, memory }))).catch(() => undefined);
    } catch (error) {
      if (controller.signal.aborted && runId) await studioApi.cancelRun(runId).catch(() => undefined);
      if (conversation) {
        const fresh = await api.getConversation(conversation.id).catch(() => null);
        setState(current => ({ ...current, ...(fresh && current.activeId === conversation!.id ? { active: fresh } : {}), streaming: false, streamError: controller.signal.aborted ? null : error instanceof Error ? error.message : t("error.generic") }));
      } else setState(current => ({ ...current, streaming: false, streamError: controller.signal.aborted ? null : error instanceof Error ? error.message : t("error.generic") }));
      void refreshConversations();
    } finally {
      startingChatRef.current = false;
      if (chatRunRef.current === runId) chatRunRef.current = null;
      if (abortRef.current === controller) abortRef.current = null;
    }
  }, [activeProvider, refreshConversations, refreshUsage, showToast, t]);

  const approveComputerCommand = useCallback(async (approve: boolean) => {
    const run = stateRef.current.chatRun;
    const pending = run?.pendingCommand ?? run?.pendingUiAction ?? run?.pendingCodexAction;
    if (!run || !pending || run.id !== chatRunRef.current) return;
    try {
      await studioApi.approveChange(run.id, pending.id, approve);
      setState(current => current.chatRun?.id === run.id ? { ...current, chatRun: { ...current.chatRun, pendingCommand: undefined, pendingUiAction: undefined, pendingCodexAction: undefined, state: "running" } } : current);
    } catch (error) { showToast(error instanceof Error ? error.message : t("error.generic"), "error"); }
  }, [showToast, t]);

  const stop = useCallback(() => {
    abortRef.current?.abort();
    if (startingChatRef.current) return;
    abortRef.current = null;
    const text = stateRef.current.streamText;
    patchAssistant(text, "stopped");
    setState((current) => ({ ...current, streaming: false }));
  }, [patchAssistant]);

  const retry = useCallback(async () => {
    const current = stateRef.current;
    if (!current.active || current.streaming) return;
    const lastUser = [...current.active.messages].reverse().find(message => message.role === "user");
    if (lastUser) await send(lastUser.content);
  }, [send]);

  const copyText = useCallback(
    async (text: string) => {
      try {
        await navigator.clipboard.writeText(text);
        showToast(t("common.copied"));
      } catch {
        showToast(t("error.generic"), "error");
      }
    },
    [showToast, t],
  );

  const updateSettings = useCallback(
    async (patch: Partial<Settings>) => {
      const previous = stateRef.current.settings;
      setState((current) =>
        current.settings ? { ...current, settings: { ...current.settings, ...patch } } : current,
      );
      try {
        const settings = await api.updateSettings(patch);
        setState((current) => ({
          ...current,
          settings,
          inspectorOpen: patch.showInspector ?? current.inspectorOpen,
        }));
      } catch (error) {
        setState((current) => ({ ...current, settings: previous }));
        showToast(error instanceof Error ? error.message : t("error.generic"), "error");
      }
    },
    [showToast, t],
  );

  const selectProvider = useCallback(
    async (id: string) => {
      await updateSettings({ activeProviderId: id });
    },
    [updateSettings],
  );

  const saveProvider = useCallback(
    async (input: Record<string, unknown>, id?: string) => {
      const provider = id ? await api.updateProvider(id, input) : await api.createProvider(input);
      const providers = await api.listProviders();
      setState((current) => ({ ...current, providers }));
      if (!stateRef.current.settings?.activeProviderId) {
        await updateSettings({ activeProviderId: provider.id });
      }
      showToast(t("models.saved"));
      return provider;
    },
    [showToast, t, updateSettings],
  );

  const deleteProvider = useCallback(
    async (id: string) => {
      await api.deleteProvider(id);
      const providers = await api.listProviders();
      setState((current) => ({ ...current, providers }));
      if (stateRef.current.settings?.activeProviderId === id) {
        const next = providers.find((provider) => provider.enabled) ?? providers[0];
        await updateSettings({ activeProviderId: next?.id ?? "" });
      }
      showToast(t("models.deleted"));
    },
    [showToast, t, updateSettings],
  );

  const testProvider = useCallback(async (id: string) => {
    const result = await api.testProvider(id);
    return { latencyMs: result.latencyMs, reply: result.reply };
  }, []);

  const fetchModels = useCallback(async (id: string) => {
    const result = await api.listModels(id);
    return result.models;
  }, []);

  const renameConversation = useCallback(
    async (id: string, title: string) => {
      const conversation = await api.updateConversation(id, { title });
      setState((current) => ({
        ...current,
        conversations: current.conversations.map((row) =>
          row.id === id ? { ...row, title: conversation.title } : row,
        ),
        active: current.active?.id === id ? { ...current.active, title: conversation.title } : current.active,
      }));
    },
    [],
  );

  const deleteConversation = useCallback(
    async (id: string) => {
      await api.deleteConversation(id);
      setState((current) => ({
        ...current,
        conversations: current.conversations.filter((row) => row.id !== id),
        active: current.active?.id === id ? null : current.active,
        activeId: current.activeId === id ? null : current.activeId,
        screen: current.activeId === id ? "assistant" : current.screen,
      }));
      showToast(t("sidebar.delete"));
    },
    [showToast, t],
  );

  const pinConversation = useCallback(async (id: string, pinned: boolean) => {
    const conversation = await api.updateConversation(id, { pinned });
    setState((current) => ({
      ...current,
      conversations: current.conversations
        .map((row) => (row.id === id ? { ...row, pinned: conversation.pinned } : row))
        .sort((a, b) => Number(b.pinned) - Number(a.pinned) || b.updatedAt - a.updatedAt),
      active: current.active?.id === id ? { ...current.active, pinned: conversation.pinned } : current.active,
    }));
  }, []);

  const saveMemory = useCallback(
    async (input: { key: string; value: string; source?: string }, id?: string) => {
      const fact = id ? await api.updateMemory(id, input) : await api.createMemory(input);
      const memory = await api.listMemory();
      setState((current) => ({ ...current, memory }));
      showToast(t("memory.saved"));
      return fact;
    },
    [showToast, t],
  );

  const deleteMemory = useCallback(
    async (id: string) => {
      await api.deleteMemory(id);
      setState((current) => ({ ...current, memory: current.memory.filter((fact) => fact.id !== id) }));
      showToast(t("memory.deleted"));
    },
    [showToast, t],
  );

  const clearMemory = useCallback(async () => {
    await api.clearMemory();
    setState((current) => ({ ...current, memory: [] }));
    showToast(t("memory.cleared"));
  }, [showToast, t]);

  const toggleTheme = useCallback(() => {
    const next = resolvedTheme === "dark" ? "light" : "dark";
    void updateSettings({ theme: next });
  }, [resolvedTheme, updateSettings]);

  const toggleLanguage = useCallback(() => {
    void updateSettings({ language: uiLanguage === "fa" ? "en" : "fa" });
  }, [uiLanguage, updateSettings]);

  const setSidebarOpen = useCallback((open: boolean) => {
    setState((current) => ({ ...current, sidebarOpen: open }));
  }, []);

  const setInspectorOpen = useCallback(
    (open: boolean) => {
      setState((current) => ({ ...current, inspectorOpen: open }));
      void updateSettings({ showInspector: open });
    },
    [updateSettings],
  );

  const setPaletteOpen = useCallback((open: boolean) => {
    setState((current) => ({ ...current, paletteOpen: open }));
  }, []);

  const setDraft = useCallback((key: string, text: string) => {
    setState((current) => ({ ...current, drafts: { ...current.drafts, [key]: text } }));
  }, []);
  const setNavigationGuard = useCallback((guard: ((proceed: () => void) => void) | null) => {
    navigationGuard.current = guard;
  }, []);

  const actions: AppActions = useMemo(
    () => ({
      t,
      uiLanguage,
      resolvedTheme,
      activeProvider,
      setScreen,
      newChat,
      setDraft,
      setNavigationGuard,
      openConversation,
      send,
      stop,
      approveComputerCommand,
      retry,
      copyText,
      renameConversation,
      deleteConversation,
      pinConversation,
      updateSettings,
      selectProvider,
      saveProvider,
      deleteProvider,
      testProvider,
      fetchModels,
      saveMemory,
      deleteMemory,
      clearMemory,
      showToast,
      toggleTheme,
      toggleLanguage,
      setSidebarOpen,
      setInspectorOpen,
      setPaletteOpen,
      refreshConversations,
      refreshUsage,
      reload: load,
    }),
    [
      activeProvider,
      approveComputerCommand,
      clearMemory,
      copyText,
      deleteConversation,
      deleteMemory,
      deleteProvider,
      fetchModels,
      load,
      newChat,
      setDraft,
      setNavigationGuard,
      openConversation,
      pinConversation,
      refreshConversations,
      refreshUsage,
      renameConversation,
      resolvedTheme,
      saveMemory,
      saveProvider,
      selectProvider,
      send,
      setInspectorOpen,
      setPaletteOpen,
      setScreen,
      setSidebarOpen,
      showToast,
      stop,
      t,
      testProvider,
      toggleLanguage,
      toggleTheme,
      uiLanguage,
      updateSettings,
      retry,
    ],
  );

  return (
    <AppContext.Provider value={{ ...state, actions, uiLanguage, resolvedTheme, activeProvider }}>
      {children}
    </AppContext.Provider>
  );
}

export function useApp() {
  const value = useContext(AppContext);
  if (!value) throw new Error("useApp must be used inside AppProvider");
  return value;
}
