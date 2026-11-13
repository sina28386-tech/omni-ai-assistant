import { ArrowUpRight, Globe2, Image, Video, WandSparkles } from "lucide-react";
import { useApp } from "../state/AppStore";
import { webWorkspaceText } from "../lib/webWorkspaceCopy";
import "../styles/web-workspace.css";

export function WebWorkspaceScreen() {
  const { uiLanguage } = useApp();
  const c = webWorkspaceText(uiLanguage);
  const services = [
    { name: "Higgsfield", url: "https://higgsfield.ai/", description: c.higgsfield, Icon: WandSparkles },
    { name: "Runway", url: "https://app.runwayml.com/", description: c.runway, Icon: Video },
    { name: "Google Flow", url: "https://labs.google/fx/tools/flow", description: c.flow, Icon: Image },
  ];

  return <main className="web-workspace">
    <header className="web-workspace__header">
      <span className="web-workspace__eyebrow"><Globe2 aria-hidden="true" /> Omni</span>
      <h1>{c.title}</h1>
      <p>{c.intro}</p>
    </header>
    <section aria-labelledby="web-workspace-services">
      <h2 id="web-workspace-services">{c.section}</h2>
      <ul className="web-workspace__list">
        {services.map(({ name, url, description, Icon }) => <li key={name}>
          <span className="web-workspace__icon"><Icon aria-hidden="true" /></span>
          <div className="web-workspace__service"><h3 dir="ltr">{name}</h3><p>{description}</p></div>
          <a className="web-workspace__link" href={url} target="_blank" rel="noopener noreferrer" aria-label={`${c.open}: ${name} (${c.newTab})`}>
            {c.open}<ArrowUpRight aria-hidden="true" />
          </a>
        </li>)}
      </ul>
    </section>
    <div className="web-workspace__notes">
      <section><h2>{c.noteTitle}</h2><p>{c.note}</p></section>
      <section><h2>{c.billingTitle}</h2><p>{c.billing}</p></section>
    </div>
  </main>;
}
