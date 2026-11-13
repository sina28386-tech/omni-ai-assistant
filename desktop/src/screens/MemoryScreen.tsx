import { useEffect, useMemo, useState } from "react";
import { Brain, Info, Pencil, Plus, RefreshCw, Search, Trash2 } from "lucide-react";
import { useApp } from "../state/AppStore";
import { relativeTime } from "../lib/format";
import type { MemoryFact } from "../lib/types";
import {
  Button,
  ConfirmDialog,
  Dialog,
  EmptyState,
  Field,
  IconButton,
  Input,
  PageHead,
  Tag,
  Textarea,
} from "../components/ui";

export function MemoryScreen() {
  const { memory, actions, uiLanguage } = useApp();
  const [query, setQuery] = useState("");
  const [editing, setEditing] = useState<MemoryFact | null>(null);
  const [formOpen, setFormOpen] = useState(false);
  const [keyValue, setKeyValue] = useState("");
  const [value, setValue] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<MemoryFact | null>(null);
  const [clearOpen, setClearOpen] = useState(false);

  const filtered = useMemo(() => {
    const needle = query.trim().toLocaleLowerCase();
    if (!needle) return memory;
    return memory.filter((fact) => `${fact.key} ${fact.value} ${fact.source}`.toLocaleLowerCase().includes(needle));
  }, [memory, query]);

  useEffect(() => {
    if (!formOpen) return;
    setError("");
    if (editing) {
      setKeyValue(editing.key);
      setValue(editing.value);
    } else {
      setKeyValue("");
      setValue("");
    }
  }, [editing, formOpen]);

  function openNew() {
    setEditing(null);
    setFormOpen(true);
  }

  function openEdit(fact: MemoryFact) {
    setEditing(fact);
    setFormOpen(true);
  }

  async function save() {
    if (!keyValue.trim()) return setError(actions.t("memory.keyHint"));
    if (!value.trim()) return setError(actions.t("memory.valueHint"));
    setBusy(true);
    setError("");
    try {
      await actions.saveMemory({ key: keyValue.trim(), value: value.trim(), source: "manual" }, editing?.id);
      setFormOpen(false);
      setEditing(null);
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : actions.t("error.generic"));
    } finally {
      setBusy(false);
    }
  }

  async function confirmDelete() {
    if (!deleteTarget) return;
    setBusy(true);
    try {
      await actions.deleteMemory(deleteTarget.id);
      setDeleteTarget(null);
    } catch (failure) {
      actions.showToast(failure instanceof Error ? failure.message : actions.t("error.generic"), "error");
    } finally {
      setBusy(false);
    }
  }

  async function confirmClear() {
    setBusy(true);
    try {
      await actions.clearMemory();
      setClearOpen(false);
    } catch (failure) {
      actions.showToast(failure instanceof Error ? failure.message : actions.t("error.generic"), "error");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="page page--narrow">
      <PageHead
        eyebrow={actions.t("memory.eyebrow")}
        title={actions.t("memory.title")}
        subtitle={actions.t("memory.subtitle")}
        actions={
          <>
            <Button variant="primary" onClick={openNew}>
              <Plus />
              {actions.t("memory.add")}
            </Button>
            {memory.length ? (
              <Button variant="ghost" onClick={() => setClearOpen(true)}>
                <Trash2 />
                {actions.t("memory.clear")}
              </Button>
            ) : null}
          </>
        }
      />

      <div className="memory-toolbar">
        <div className="sidebar__search memory-search">
          <Search />
          <input
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder={actions.t("memory.search")}
            aria-label={actions.t("memory.search")}
            type="search"
          />
        </div>
        <Tag tone="ember">{actions.t("memory.count", { count: memory.length })}</Tag>
      </div>

      {memory.length === 0 ? (
        <div className="memory-empty">
          <EmptyState
            icon={<Brain />}
            title={actions.t("memory.emptyTitle")}
            text={actions.t("memory.emptyText")}
            actions={
              <Button variant="primary" onClick={openNew}>
                <Plus />
                {actions.t("memory.add")}
              </Button>
            }
          />
        </div>
      ) : filtered.length === 0 ? (
        <div className="memory-empty">
          <EmptyState icon={<Search />} title={actions.t("memory.noResults")} text={actions.t("memory.search")} />
        </div>
      ) : (
        <div className="memory-list">
          {filtered.map((fact) => (
            <article className="memory-card" key={fact.id}>
              <div className="memory-card__main">
                <div className="memory-card__key">
                  {fact.key}
                  {fact.source === "manual" ? <Tag>{actions.t("memory.manual")}</Tag> : <Tag tone="steel">{fact.source}</Tag>}
                </div>
                <div className="memory-card__value">{fact.value}</div>
                <div className="memory-card__meta">
                  {actions.t("memory.updated", { time: relativeTime(fact.updatedAt, uiLanguage, actions.t) })}
                </div>
              </div>
              <div className="memory-card__actions">
                <IconButton label={actions.t("memory.edit")} size="sm" onClick={() => openEdit(fact)}>
                  <Pencil />
                </IconButton>
                <IconButton label={actions.t("common.delete")} size="sm" danger onClick={() => setDeleteTarget(fact)}>
                  <Trash2 />
                </IconButton>
              </div>
            </article>
          ))}
        </div>
      )}

      <div className="recall-note">
        <Info />
        <div>
          <div className="recall-note__title">{actions.t("memory.howTitle")}</div>
          <p>{actions.t("memory.howText")}</p>
        </div>
      </div>

      <Dialog
        open={formOpen}
        onClose={() => setFormOpen(false)}
        title={editing ? actions.t("memory.edit") : actions.t("memory.add")}
        subtitle={actions.t("memory.subtitle")}
        footer={
          <>
            <Button variant="ghost" onClick={() => setFormOpen(false)} disabled={busy}>
              {actions.t("common.cancel")}
            </Button>
            <Button variant="primary" onClick={() => void save()} disabled={busy}>
              {busy ? <RefreshCw className="spin" /> : null}
              {actions.t("common.save")}
            </Button>
          </>
        }
      >
        <div className="form">
          {error ? <div className="form__error">{error}</div> : null}
          <Field label={actions.t("memory.key")} hint={actions.t("memory.keyHint")} htmlFor="memory-key">
            <Input
              id="memory-key"
              value={keyValue}
              onChange={(event) => setKeyValue(event.target.value)}
              placeholder={actions.t("memory.keyHint")}
              autoFocus
            />
          </Field>
          <Field label={actions.t("memory.value")} hint={actions.t("memory.valueHint")} htmlFor="memory-value">
            <Textarea
              id="memory-value"
              value={value}
              onChange={(event) => setValue(event.target.value)}
              rows={4}
              placeholder={actions.t("memory.valueHint")}
            />
          </Field>
        </div>
      </Dialog>

      <ConfirmDialog
        open={Boolean(deleteTarget)}
        onClose={() => setDeleteTarget(null)}
        onConfirm={() => void confirmDelete()}
        title={actions.t("memory.deleteTitle")}
        body={actions.t("memory.deleteBody", { key: deleteTarget?.key ?? "" })}
        confirmLabel={actions.t("common.delete")}
        cancelLabel={actions.t("common.cancel")}
        danger
        busy={busy}
      />
      <ConfirmDialog
        open={clearOpen}
        onClose={() => setClearOpen(false)}
        onConfirm={() => void confirmClear()}
        title={actions.t("memory.clearTitle")}
        body={actions.t("memory.clearBody")}
        confirmLabel={actions.t("memory.clear")}
        cancelLabel={actions.t("common.cancel")}
        danger
        busy={busy}
      />
    </div>
  );
}
