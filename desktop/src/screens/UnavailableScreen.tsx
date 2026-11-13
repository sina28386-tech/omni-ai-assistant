import { ArrowLeft, Bot, Code2, FileText, Smartphone } from "lucide-react";
import { useApp } from "../state/AppStore";
import type { Screen } from "../lib/types";
import { Button, Tag } from "../components/ui";

const config = {
  agents: {
    icon: <Bot />,
    title: "agents.stateTitle",
    text: "agents.stateText",
    facts: [
      ["agents.factRuntime", "agents.factRuntimeValue", "off"],
      ["agents.factContracts", "agents.factContractsValue", "on"],
      ["agents.factFake", "agents.factFakeValue", "on"],
    ],
    doc: "/docs/AGENT_CONTRACTS.md",
  },
  code: {
    icon: <Code2 />,
    title: "code.stateTitle",
    text: "code.stateText",
    facts: [
      ["code.factWorkspace", "code.factWorkspaceValue", "off"],
      ["code.factTools", "code.factToolsValue", "off"],
      ["code.factApproval", "code.factApprovalValue", "on"],
    ],
    doc: "/docs/CODING_WORKSPACE.md",
  },
  devices: {
    icon: <Smartphone />,
    title: "devices.stateTitle",
    text: "devices.stateText",
    facts: [
      ["devices.factMobile", "devices.factMobileValue", "on"],
      ["devices.factTls", "devices.factTlsValue", "off"],
      ["devices.factRemote", "devices.factRemoteValue", "off"],
    ],
    doc: "/docs/DEVICE_PAIRING.md",
  },
} as const;

export function UnavailableScreen({ screen }: { screen: Extract<Screen, "agents" | "code" | "devices"> }) {
  const { actions } = useApp();
  const item = config[screen];
  const eyebrow = screen === "agents" ? "agents.eyebrow" : screen === "code" ? "code.eyebrow" : "devices.eyebrow";
  const subtitle = screen === "agents" ? "agents.subtitle" : screen === "code" ? "code.subtitle" : "devices.subtitle";

  return (
    <div className="runtime-state">
      <div className="runtime-state__inner">
        <div className="runtime-state__mark">{item.icon}</div>
        <div className="page__eyebrow">{actions.t(eyebrow)}</div>
        <h1 className="runtime-state__title">{actions.t(item.title)}</h1>
        <p className="runtime-state__sub">{actions.t(subtitle)}</p>
        <p className="runtime-state__text">{actions.t(item.text)}</p>
        <Tag tone="amber">{actions.t("common.unavailable")}</Tag>

        <div className="runtime-state__facts">
          {item.facts.map(([label, value, tone]) => (
            <div className="runtime-fact" key={label}>
              <div className="runtime-fact__label">{actions.t(label)}</div>
              <div className={`runtime-fact__value${tone === "off" ? " runtime-fact__value--off" : ""}`}>
                {actions.t(value)}
              </div>
            </div>
          ))}
        </div>

        <div className="runtime-state__actions">
          <Button variant="primary" onClick={() => actions.setScreen("assistant")}>
            <ArrowLeft className="flip" />
            {actions.t("runtime.back")}
          </Button>
          <a className="btn btn--secondary btn--md" href={item.doc} target="_blank" rel="noreferrer">
            <FileText />
            {actions.t("runtime.docs")}
          </a>
        </div>

        <p className="runtime-state__fine">{actions.t("runtime.text")}</p>
      </div>
    </div>
  );
}
