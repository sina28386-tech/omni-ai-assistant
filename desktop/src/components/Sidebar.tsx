import { useEffect, useMemo, useRef, useState } from "react";
import { Bot, Brain, Code2, Cpu, Globe, Layers, LayoutDashboard, MessageCircle, Moon, MoreHorizontal, Pencil, Pin, Plus, Search, Settings2, Smartphone, Sun, Trash2, X } from "lucide-react";
import { useApp } from "../state/AppStore";
import { groupConversations, relativeTime } from "../lib/format";
import type { MessageKey } from "../lib/i18n";
import type { ConversationSummary } from "../lib/types";
import { Button, ConfirmDialog, Dialog, IconButton, Input, LogoMark } from "./ui";

const bucketLabel = {
  pinned: "sidebar.pinned",
  today: "sidebar.today",
  yesterday: "sidebar.yesterday",
  previous7: "sidebar.previous7",
  older: "sidebar.older",
} as const;

export function Sidebar() {
  const { conversations, activeId, screen, settings, resolvedTheme, actions, uiLanguage } = useApp();
  const [query, setQuery] = useState("");
  const [renameTarget, setRenameTarget] = useState<ConversationSummary | null>(null);
  const [renameValue, setRenameValue] = useState("");
  const [deleteTarget, setDeleteTarget] = useState<ConversationSummary | null>(null);
  const [busy, setBusy] = useState(false);

  const filtered = useMemo(() => {
    const needle = query.trim().toLocaleLowerCase();
    if (!needle) return conversations;
    return conversations.filter((row) =>
      `${row.title} ${row.preview}`.toLocaleLowerCase().includes(needle),
    );
  }, [conversations, query]);
  const groups = useMemo(() => groupConversations(filtered), [filtered]);

  async function submitRename() {
    if (!renameTarget) return;
    const title = renameValue.trim();
    if (!title) return;
    setBusy(true);
    try {
      await actions.renameConversation(renameTarget.id, title);
      setRenameTarget(null);
    } catch (error) {
      actions.showToast(error instanceof Error ? error.message : actions.t("error.generic"), "error");
    } finally {
      setBusy(false);
    }
  }

  async function confirmDelete() {
    if (!deleteTarget) return;
    setBusy(true);
    try {
      await actions.deleteConversation(deleteTarget.id);
      setDeleteTarget(null);
    } catch (error) {
      actions.showToast(error instanceof Error ? error.message : actions.t("error.generic"), "error");
    } finally {
      setBusy(false);
    }
  }

  return (
    <aside className="sidebar" aria-label={actions.t("sidebar.show")}>
      <div className="sidebar__head">
        <div className="sidebar__wordmark">
          <LogoMark size={30} /><span className="sidebar__brand-text">OMNI</span>
        </div>
        <div className="sidebar__head-actions">
          <IconButton
            className="sidebar__close"
            label={actions.t("common.close")}
            onClick={() => actions.setSidebarOpen(false)}
            size="sm"
          >
            <X />
          </IconButton>
        </div>
      </div>

      <div className="sidebar__tools">
        <Button variant="primary" block onClick={actions.newChat}>
          <Plus />
          {actions.t("sidebar.newChat")}
        </Button>
        <div className="sidebar__search">
          <Search />
          <input
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder={actions.t("sidebar.search")}
            aria-label={actions.t("sidebar.search")}
            type="search"
          />
        </div>
      </div>

      <nav className="sidebar__nav" aria-label={actions.t("common.appName")}>
        {([
          ["assistant", "nav.assistant", MessageCircle],
          ["workspace", "nav.workspace", LayoutDashboard],
          ["agents", "nav.agents", Bot],
          ["code", "nav.code", Code2],
          ["web", "nav.web", Globe],
          ["memory", "nav.memory", Brain],
          ["models", "nav.models", Cpu],
        ] as const).map(([id, label, Icon]) => (
          <button type="button" key={id} className="sidebar__nav-item" aria-current={screen === id ? "page" : undefined} onClick={() => actions.setScreen(id)}>
            <Icon /><span>{actions.t(label)}</span>
          </button>
        ))}
        <details className="sidebar__advanced">
          <summary>{actions.t("common.studio")}</summary>
          {([
            ["devices", "nav.devices", Smartphone],
          ] as const).map(([id, label, Icon]) => (
            <button type="button" key={id} className="sidebar__nav-item" aria-current={screen === id ? "page" : undefined} onClick={() => {
              void actions.updateSettings({ mode: "studio" }).then(() => actions.setScreen(id));
            }}><Icon /><span>{actions.t(label)}</span></button>
          ))}
        </details>
      </nav>

      <div className="sidebar__scroll">
        {groups.length === 0 ? (
          <div className="sidebar__empty">
            {query.trim() ? actions.t("sidebar.searchEmpty") : actions.t("sidebar.noConversationsHint")}
          </div>
        ) : (
          groups.map((group) => (
            <div className="session-group" key={group.bucket}>
              <div className="session-group__label">
                <span>{actions.t(bucketLabel[group.bucket])}</span>
                <span>{group.items.length}</span>
              </div>
              {group.items.map((row) => (
                <SessionRow
                  key={row.id}
                  row={row}
                  selected={screen === "assistant" && activeId === row.id}
                  time={relativeTime(row.updatedAt, uiLanguage, actions.t)}
                  onOpen={() => void actions.openConversation(row.id)}
                  onRename={() => {
                    setRenameTarget(row);
                    setRenameValue(row.title || actions.t("common.untitled"));
                  }}
                  onDelete={() => setDeleteTarget(row)}
                  onPin={() => {
                    void actions.pinConversation(row.id, !row.pinned).catch((error: unknown) => {
                      actions.showToast(error instanceof Error ? error.message : actions.t("error.generic"), "error");
                    });
                  }}
                  t={actions.t}
                />
              ))}
            </div>
          ))
        )}
      </div>

      <div className="sidebar__footer">
        <button type="button" className="sidebar__nav-item" aria-current={screen === "settings" ? "page" : undefined} onClick={() => actions.setScreen("settings")}><Settings2 /><span>{actions.t("nav.settings")}</span></button>
        <div className="sidebar__preferences">
          <IconButton label={actions.t("settings.theme")} onClick={actions.toggleTheme} size="sm">{resolvedTheme === "dark" ? <Sun /> : <Moon />}</IconButton>
          <IconButton label={actions.t("settings.language")} onClick={actions.toggleLanguage} size="sm"><Globe /></IconButton>
          <IconButton label={actions.t("nav.switchExperience")} onClick={() => void actions.updateSettings({ mode: settings?.mode === "studio" ? "simple" : "studio" })} size="sm"><Layers /></IconButton>
          <span>{uiLanguage === "fa" ? "\u0641\u0627\u0631\u0633\u06cc" : "English"} · {actions.t(settings?.mode === "studio" ? "common.studio" : "common.simple")}</span>
        </div>
      </div>

      <Dialog
        open={Boolean(renameTarget)}
        onClose={() => setRenameTarget(null)}
        title={actions.t("sidebar.renameTitle")}
        footer={
          <>
            <Button variant="ghost" onClick={() => setRenameTarget(null)} disabled={busy}>
              {actions.t("common.cancel")}
            </Button>
            <Button variant="primary" onClick={() => void submitRename()} disabled={busy || !renameValue.trim()}>
              {actions.t("common.save")}
            </Button>
          </>
        }
      >
        <Input
          value={renameValue}
          onChange={(event) => setRenameValue(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === "Enter") void submitRename();
          }}
          aria-label={actions.t("sidebar.renameTitle")}
          autoFocus
        />
      </Dialog>

      <ConfirmDialog
        open={Boolean(deleteTarget)}
        onClose={() => setDeleteTarget(null)}
        onConfirm={() => void confirmDelete()}
        title={actions.t("sidebar.deleteTitle")}
        body={actions.t("sidebar.deleteBody")}
        confirmLabel={actions.t("common.delete")}
        cancelLabel={actions.t("common.cancel")}
        danger
        busy={busy}
      />
    </aside>
  );
}

function SessionRow({
  row,
  selected,
  time,
  onOpen,
  onRename,
  onDelete,
  onPin,
  t,
}: {
  row: ConversationSummary;
  selected: boolean;
  time: string;
  onOpen: () => void;
  onRename: () => void;
  onDelete: () => void;
  onPin: () => void;
  t: (key: MessageKey, vars?: Record<string, string | number>) => string;
}) {
  const [menu, setMenu] = useState(false);
  const wrap = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!menu) return;
    const close = (event: MouseEvent) => {
      if (!wrap.current?.contains(event.target as Node)) setMenu(false);
    };
    const escape = (event: KeyboardEvent) => {
      if (event.key === "Escape") setMenu(false);
    };
    document.addEventListener("mousedown", close);
    document.addEventListener("keydown", escape);
    return () => {
      document.removeEventListener("mousedown", close);
      document.removeEventListener("keydown", escape);
    };
  }, [menu]);

  return (
    <div className="session-row-wrap" ref={wrap}>
      <button
        type="button"
        className="session-row"
        aria-current={selected ? "true" : undefined}
        onClick={onOpen}
        title={row.title || ""}
      >
        <span className="session-row__title">
          {row.pinned ? <Pin className="session-row__pin" aria-hidden="true" /> : null}
          {row.title || t("common.untitled")}
        </span>
        <span className="session-row__meta">
          <span>{time}</span>
          <span aria-hidden="true">·</span>
          <span>{row.preview || t("common.untitled")}</span>
        </span>
      </button>
      <div className="session-row__menu">
        <IconButton
          label={t("sidebar.more")}
          size="sm"
          aria-haspopup="menu"
          aria-expanded={menu}
          onClick={(event) => {
            event.stopPropagation();
            setMenu((value) => !value);
          }}
        >
          <MoreHorizontal />
        </IconButton>
      </div>
      {menu ? (
        <div className="menu session-menu" role="menu">
          <button
            type="button"
            className="menu__item"
            role="menuitem"
            onClick={() => {
              setMenu(false);
              onPin();
            }}
          >
            <Pin /> {row.pinned ? t("sidebar.unpin") : t("sidebar.pin")}
          </button>
          <button
            type="button"
            className="menu__item"
            role="menuitem"
            onClick={() => {
              setMenu(false);
              onRename();
            }}
          >
            <Pencil /> {t("sidebar.rename")}
          </button>
          <div className="menu__sep" />
          <button
            type="button"
            className="menu__item menu__item--danger"
            role="menuitem"
            onClick={() => {
              setMenu(false);
              onDelete();
            }}
          >
            <Trash2 /> {t("sidebar.delete")}
          </button>
        </div>
      ) : null}
    </div>
  );
}
