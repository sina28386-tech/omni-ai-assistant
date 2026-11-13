import { useEffect, useMemo, useRef, useState, type KeyboardEvent as ReactKeyboardEvent, type ReactNode } from "react";
import {
  Brain,
  Bot,
  Code2,
  CornerDownLeft,
  Cpu,
  Globe,
  LayoutDashboard,
  MessageSquarePlus,
  Moon,
  PanelRight,
  Search,
  Settings2,
  Sparkles,
  Sun,
} from "lucide-react";
import { useApp } from "../state/AppStore";
import type { MessageKey } from "../lib/i18n";
import { Dialog } from "./ui";

interface PaletteItem {
  id: string;
  label: string;
  hint?: string;
  icon: ReactNode;
  section: "actions" | "conversations";
  run: () => void;
}

export function CommandPalette() {
  const { paletteOpen, conversations, actions } = useApp();
  const [query, setQuery] = useState("");
  const [index, setIndex] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (!paletteOpen) return;
    setQuery("");
    setIndex(0);
    const timer = setTimeout(() => inputRef.current?.focus(), 30);
    return () => clearTimeout(timer);
  }, [paletteOpen]);

  const items = useMemo<PaletteItem[]>(() => {
    const closeAnd = (run: () => void) => () => {
      actions.setPaletteOpen(false);
      run();
    };
    const base: PaletteItem[] = [
      {
        id: "new-chat",
        label: actions.t("palette.newChat"),
        icon: <MessageSquarePlus />,
        section: "actions",
        run: closeAnd(actions.newChat),
      },
      {
        id: "assistant",
        label: actions.t("palette.goAssistant"),
        icon: <Sparkles />,
        section: "actions",
        run: closeAnd(() => actions.setScreen("assistant")),
      },
      {
        id: "workspace",
        label: actions.t("palette.goWorkspace"),
        icon: <LayoutDashboard />,
        section: "actions",
        run: closeAnd(() => actions.setScreen("workspace")),
      },
      {
        id: "bots",
        label: actions.t("nav.agents"),
        icon: <Bot />,
        section: "actions",
        run: closeAnd(() => actions.setScreen("agents")),
      },
      {
        id: "code",
        label: actions.t("nav.code"),
        icon: <Code2 />,
        section: "actions",
        run: closeAnd(() => actions.setScreen("code")),
      },
      {
        id: "web",
        label: actions.t("nav.web"),
        icon: <Globe />,
        section: "actions",
        run: closeAnd(() => actions.setScreen("web")),
      },
      {
        id: "memory",
        label: actions.t("palette.goMemory"),
        icon: <Brain />,
        section: "actions",
        run: closeAnd(() => actions.setScreen("memory")),
      },
      {
        id: "models",
        label: actions.t("palette.goModels"),
        icon: <Cpu />,
        section: "actions",
        run: closeAnd(() => actions.setScreen("models")),
      },
      {
        id: "settings",
        label: actions.t("palette.goSettings"),
        icon: <Settings2 />,
        section: "actions",
        run: closeAnd(() => actions.setScreen("settings")),
      },
      {
        id: "theme",
        label: actions.t("palette.toggleTheme"),
        icon: actions.resolvedTheme === "dark" ? <Sun /> : <Moon />,
        section: "actions",
        run: closeAnd(actions.toggleTheme),
      },
      {
        id: "language",
        label: actions.t("palette.toggleLanguage"),
        hint: actions.uiLanguage === "fa" ? "English" : "فارسی",
        icon: <span className="palette__lang">Aa</span>,
        section: "actions",
        run: closeAnd(actions.toggleLanguage),
      },
      {
        id: "inspector",
        label: actions.t("palette.toggleInspector"),
        icon: <PanelRight />,
        section: "actions",
        run: closeAnd(() => actions.setInspectorOpen(true)),
      },
    ];
    const needle = query.trim().toLocaleLowerCase();
    const matchingActions = needle
      ? base.filter((item) => item.label.toLocaleLowerCase().includes(needle))
      : base;
    const matchingConversations = needle
      ? conversations
          .filter((row) => `${row.title} ${row.preview}`.toLocaleLowerCase().includes(needle))
          .slice(0, 8)
          .map<PaletteItem>((row) => ({
            id: `conversation-${row.id}`,
            label: row.title || actions.t("common.untitled"),
            hint: row.preview,
            icon: <MessageSquarePlus />,
            section: "conversations",
            run: closeAnd(() => void actions.openConversation(row.id)),
          }))
      : conversations.slice(0, 4).map<PaletteItem>((row) => ({
          id: `conversation-${row.id}`,
          label: row.title || actions.t("common.untitled"),
          hint: row.preview,
          icon: <MessageSquarePlus />,
          section: "conversations",
          run: closeAnd(() => void actions.openConversation(row.id)),
        }));
    return [...matchingActions, ...matchingConversations];
  }, [actions, conversations, query]);

  useEffect(() => {
    setIndex((value) => Math.min(Math.max(0, value), Math.max(0, items.length - 1)));
  }, [items.length]);

  const activeId = items[index]?.id;
  useEffect(() => {
    if (!paletteOpen || !activeId) return;
    document.getElementById(`palette-${activeId}`)?.scrollIntoView({ block: "nearest" });
  }, [activeId, paletteOpen]);

  function onKeyDown(event: ReactKeyboardEvent<HTMLDivElement>) {
    if (event.key === "ArrowDown") {
      event.preventDefault();
      setIndex((value) => (items.length ? (value + 1) % items.length : 0));
    } else if (event.key === "ArrowUp") {
      event.preventDefault();
      setIndex((value) => (items.length ? (value - 1 + items.length) % items.length : 0));
    } else if (event.key === "Enter") {
      event.preventDefault();
      items[index]?.run();
    }
  }

  const sections: Array<{ key: "actions" | "conversations"; label: MessageKey }> = [
    { key: "actions", label: "palette.actions" },
    { key: "conversations", label: "palette.conversations" },
  ];
  let flatIndex = -1;

  return (
    <Dialog
      open={paletteOpen}
      onClose={() => actions.setPaletteOpen(false)}
      bare
      className="palette"
    >
      <div onKeyDown={onKeyDown}>
        <div className="palette__top">
          <Search />
          <input
            ref={inputRef}
            className="palette__input"
            value={query}
            onChange={(event) => {
              setQuery(event.target.value);
              setIndex(0);
            }}
            placeholder={actions.t("palette.placeholder")}
            aria-label={actions.t("palette.placeholder")}
            role="combobox"
            aria-autocomplete="list"
            aria-haspopup="listbox"
            aria-expanded="true"
            aria-activedescendant={items[index] ? `palette-${items[index].id}` : undefined}
            aria-controls="palette-list"
          />
          <span className="palette__esc">
            <kbd>Esc</kbd>
          </span>
        </div>
        <div
          className="palette__list"
          id="palette-list"
          role="listbox"
          aria-label={actions.t("palette.placeholder")}
        >
          {items.length === 0 ? (
            <div className="palette__empty" role="status">
              {actions.t("palette.noResults")}
            </div>
          ) : (
            sections.map((section) => {
              const sectionItems = items.filter((item) => item.section === section.key);
              if (!sectionItems.length) return null;
              return (
                <div key={section.key} role="group" aria-label={actions.t(section.label)}>
                  <div className="palette__section" aria-hidden="true">
                    {actions.t(section.label)}
                  </div>
                  {sectionItems.map((item) => {
                    flatIndex += 1;
                    const selected = flatIndex === index;
                    return (
                      <button
                        key={item.id}
                        id={`palette-${item.id}`}
                        type="button"
                        role="option"
                        aria-selected={selected}
                        tabIndex={-1}
                        className="palette__item"
                        onMouseEnter={() => setIndex(items.indexOf(item))}
                        onClick={item.run}
                      >
                        {item.icon}
                        <span className="palette__label">{item.label}</span>
                        {item.hint ? <small>{item.hint}</small> : null}
                        {selected ? <CornerDownLeft className="palette__enter" /> : null}
                      </button>
                    );
                  })}
                </div>
              );
            })
          )}
        </div>
      </div>
    </Dialog>
  );
}
