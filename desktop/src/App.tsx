import {
  useEffect,
  useRef,
  useState,
  lazy,
  Suspense,
  type CSSProperties,
  type KeyboardEvent as ReactKeyboardEvent,
  type PointerEvent as ReactPointerEvent,
} from "react";
import { useApp } from "./state/AppStore";
import { Sidebar } from "./components/Sidebar";
import { Topbar } from "./components/Topbar";
import { Inspector } from "./components/Inspector";
import { CommandPalette } from "./components/CommandPalette";
import { Toast } from "./components/Toast";
import { LogoMark, Spinner } from "./components/ui";
import { AssistantScreen } from "./screens/AssistantScreen";
import { UnavailableScreen } from "./screens/UnavailableScreen";

const WorkspaceScreen = lazy(() => import("./screens/WorkspaceScreen").then((module) => ({ default: module.WorkspaceScreen })));
const MemoryScreen = lazy(() => import("./screens/MemoryScreen").then((module) => ({ default: module.MemoryScreen })));
const ModelsScreen = lazy(() => import("./screens/ModelsScreen").then((module) => ({ default: module.ModelsScreen })));
const SettingsScreen = lazy(() => import("./screens/SettingsScreen").then((module) => ({ default: module.SettingsScreen })));
const BotsScreen = lazy(() => import("./screens/BotsScreen").then((module) => ({ default: module.BotsScreen })));
const CodeScreen = lazy(() => import("./screens/CodeScreen").then((module) => ({ default: module.CodeScreen })));
const WebWorkspaceScreen = lazy(() => import("./screens/WebWorkspaceScreen").then((module) => ({ default: module.WebWorkspaceScreen })));

export default function App() {
  const { boot, bootError, settings, screen, inspectorOpen, sidebarOpen, actions } = useApp();
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);
  const [sidebarWidth, setSidebarWidth] = useState(() => readWidth("omni.sidebarWidth", 260, 232, 420));
  const [inspectorWidth, setInspectorWidth] = useState(() => readWidth("omni.inspectorWidth", 324, 272, 430));
  const appRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!settings) return;
    if (settings.mode === "simple" && screen === "devices") {
      actions.setScreen("assistant");
    }
  }, [actions, screen, settings]);

  useEffect(() => {
    if (!sidebarOpen) return;
    const close = (event: KeyboardEvent) => {
      if (event.key === "Escape") actions.setSidebarOpen(false);
    };
    document.addEventListener("keydown", close);
    return () => document.removeEventListener("keydown", close);
  }, [actions, sidebarOpen]);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      const mod = event.metaKey || event.ctrlKey;
      if (mod && event.key.toLowerCase() === "k") {
        event.preventDefault();
        actions.setPaletteOpen(true);
      } else if (mod && event.key.toLowerCase() === "n") {
        event.preventDefault();
        actions.newChat();
      } else if (mod && event.key === ",") {
        event.preventDefault();
        actions.setScreen("settings");
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [actions]);

  useEffect(() => {
    localStorage.setItem("omni.sidebarWidth", String(sidebarWidth));
  }, [sidebarWidth]);
  useEffect(() => {
    localStorage.setItem("omni.inspectorWidth", String(inspectorWidth));
  }, [inspectorWidth]);

  if (boot === "loading") {
    return (
      <div className="boot">
        <LogoMark size={44} />
        <div className="boot__wordmark">OMNI</div>
        <Spinner label={actions.t("common.loading")} />
      </div>
    );
  }

  if (boot === "error") {
    return (
      <div className="boot boot--error">
        <div className="boot__mark">
          <LogoMark size={40} />
        </div>
        <h1>{actions.t("error.bootTitle")}</h1>
        <p>{actions.t("error.bootText")}</p>
        <code className="boot__error">{bootError}</code>
        <button type="button" className="btn btn--primary btn--md" onClick={() => void actions.reload()}>
          {actions.t("error.retry")}
        </button>
      </div>
    );
  }

  const style = {
    "--sidebar-w": `${sidebarWidth}px`,
    "--inspector-w": `${inspectorWidth}px`,
  } as CSSProperties;
  const showInspector = screen === "assistant" && inspectorOpen;

  return (
    <>
      <a className="skip-link" href="#main">
        {actions.t("nav.assistant")}
      </a>
      <div
        ref={appRef}
        className="app"
        data-sidebar={sidebarOpen ? "open" : "closed"}
        data-screen={screen}
        data-sidebar-desktop={sidebarCollapsed ? "closed" : "open"}
        style={style}
      >
        <button
          type="button"
          className="sidebar-backdrop"
          aria-label={actions.t("common.close")}
          onClick={() => actions.setSidebarOpen(false)}
        />
        <Sidebar />
        <PanelResizer
          className="resizer--sidebar"
          label={actions.t("topbar.toggleSidebar")}
          value={sidebarWidth}
          min={232}
          max={420}
          direction={actions.uiLanguage === "fa" ? "rtl" : "ltr"}
          onChange={setSidebarWidth}
        />
        <main className="main" id="main">
          <Topbar onToggleSidebar={() => {
            if (window.matchMedia("(max-width: 900px)").matches) actions.setSidebarOpen(!sidebarOpen);
            else setSidebarCollapsed((collapsed) => !collapsed);
          }} />
          <div className={`content${screen === "assistant" ? " content--fixed" : ""}`}>
            <Suspense fallback={<div className="studio-empty"><Spinner label={actions.t("common.loading")} /></div>}>
            {screen === "assistant" ? <AssistantScreen /> : null}
            {screen === "workspace" ? <WorkspaceScreen /> : null}
            {screen === "memory" ? <MemoryScreen /> : null}
            {screen === "models" ? <ModelsScreen /> : null}
            {screen === "settings" ? <SettingsScreen /> : null}
            {screen === "agents" ? <BotsScreen /> : null}
            {screen === "code" ? <CodeScreen /> : null}
            {screen === "web" ? <WebWorkspaceScreen /> : null}
            {screen === "devices" ? (
              <UnavailableScreen screen={screen} />
            ) : null}
            </Suspense>
          </div>
        </main>
        {showInspector ? (
          <>
            <PanelResizer
              className="resizer--inspector"
              label={actions.t("topbar.toggleInspector")}
              value={inspectorWidth}
              min={272}
              max={430}
              direction={actions.uiLanguage === "fa" ? "rtl" : "ltr"}
              onChange={setInspectorWidth}
              invert
            />
            <Inspector />
          </>
        ) : null}
        <CommandPalette />
        <Toast />
      </div>
    </>
  );
}

function readWidth(key: string, fallback: number, min: number, max: number): number {
  const raw = Number(localStorage.getItem(key));
  if (!Number.isFinite(raw) || raw < min || raw > max) return fallback;
  return raw;
}

function PanelResizer({
  className,
  label,
  value,
  min,
  max,
  onChange,
  direction,
  invert,
}: {
  className: string;
  label: string;
  value: number;
  min: number;
  max: number;
  onChange: (value: number) => void;
  direction: "rtl" | "ltr";
  invert?: boolean;
}) {
  const start = useRef<{ x: number; value: number } | null>(null);

  function onPointerDown(event: ReactPointerEvent<HTMLDivElement>) {
    event.preventDefault();
    start.current = { x: event.clientX, value };
    document.body.style.cursor = "col-resize";
    document.body.style.userSelect = "none";
    const move = (moveEvent: PointerEvent) => {
      if (!start.current) return;
      const raw = moveEvent.clientX - start.current.x;
      const signed = direction === "rtl" ? -raw : raw;
      const delta = invert ? -signed : signed;
      onChange(Math.min(max, Math.max(min, start.current.value + delta)));
    };
    const up = () => {
      start.current = null;
      document.body.style.cursor = "";
      document.body.style.userSelect = "";
      window.removeEventListener("pointermove", move);
      window.removeEventListener("pointerup", up);
    };
    window.addEventListener("pointermove", move);
    window.addEventListener("pointerup", up);
  }

  function onKeyDown(event: ReactKeyboardEvent<HTMLDivElement>) {
    if (event.key !== "ArrowLeft" && event.key !== "ArrowRight") return;
    event.preventDefault();
    const physical = event.key === "ArrowRight" ? 1 : -1;
    const signed = direction === "rtl" ? -physical : physical;
    const delta = invert ? -signed : signed;
    onChange(Math.min(max, Math.max(min, value + delta * 16)));
  }

  return (
    <div
      className={`resizer ${className}`}
      role="separator"
      aria-orientation="vertical"
      aria-label={label}
      aria-valuemin={min}
      aria-valuemax={max}
      aria-valuenow={value}
      tabIndex={0}
      onPointerDown={onPointerDown}
      onKeyDown={onKeyDown}
    />
  );
}
