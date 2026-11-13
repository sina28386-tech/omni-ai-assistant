import { useState } from "react";
import { ArrowLeft, MonitorCog } from "lucide-react";
import { useApp } from "../state/AppStore";
import { studioApi, type Bot } from "../lib/studioApi";
import { Button, Spinner } from "./ui";
import { BotTaskPanel } from "./BotTaskPanel";

/** Explicit computer mode uses a dedicated coworker and the selected model profile. */
export function ComputerAssistant({ onBack }: { onBack: () => void }) {
  const { activeProvider, actions, uiLanguage } = useApp();
  const [bot, setBot] = useState<Bot | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const fa = uiLanguage === "fa";

  async function start() {
    if (!activeProvider?.enabled || loading) return;
    setLoading(true); setError("");
    try {
      const key = `omni.computerBot.${activeProvider.id}`;
      const id = localStorage.getItem(key);
      const found = id ? (await studioApi.listBots()).find((row) => row.id === id && row.providerId === activeProvider.id && row.allowCommands === true && row.workspaceId === null) : undefined;
      const selected = found ?? await studioApi.createBot({ name: fa ? "دستیار رایانه" : "Computer assistant", instructions: "Help the user operate their Windows computer. Use the available tools when action is requested. Explain results accurately and seek exact command approval.", providerId: activeProvider.id, workspaceId: null, allowCommands: true });
      localStorage.setItem(key, selected.id);
      setBot(selected);
    } catch (failure) { setError(failure instanceof Error ? failure.message : actions.t("error.generic")); }
    finally { setLoading(false); }
  }

  return <div className="studio-page computer-page">
    <div className="computer-page__head"><Button variant="ghost" onClick={onBack}><ArrowLeft />{fa ? "بازگشت به گفتگو" : "Back to chat"}</Button></div>
    <header className="studio-page__head"><h1><MonitorCog aria-hidden="true" /> {fa ? "دستیار رایانه" : "Computer assistant"}</h1><p>{fa ? "کاری را که می‌خواهید روی ویندوز انجام شود توضیح دهید." : "Describe what you want to do on Windows."}</p></header>
    <p className="computer-page__notice">{fa ? "هر دستور PowerShell یا CMD پیش از اجرا کامل نمایش داده می‌شود و به تأیید شما نیاز دارد. دستور تأییدشده با دسترسی حساب ویندوز شما اجرا می‌شود و خروجی به مدل انتخابی ارسال می‌شود." : "Every PowerShell or CMD command is shown in full and requires your approval. Approved commands run with your Windows account's permissions, and output is sent to the selected model."}</p>
    {error ? <p role="alert" className="studio-error">{error}</p> : null}
    {!activeProvider?.enabled ? <div className="studio-empty"><p>{fa ? "ابتدا یک سرویس و مدل فعال انتخاب کنید." : "Select an enabled provider and model first."}</p><Button onClick={() => actions.setScreen("models")}>{fa ? "انتخاب مدل" : "Choose model"}</Button></div> : null}
    {activeProvider?.enabled && !bot ? <div className="studio-empty"><Button variant="primary" disabled={loading} onClick={() => void start()}>{loading ? <Spinner label={fa ? "در حال آماده‌سازی" : "Preparing"} /> : null}{fa ? "فعال‌کردن دستیار رایانه" : "Enable computer assistant"}</Button></div> : null}
    {bot ? <section className="computer-page__tasks"><BotTaskPanel bot={bot} /></section> : null}
  </div>;
}
