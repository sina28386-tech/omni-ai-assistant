# Security

Please report suspected vulnerabilities privately through the repository's GitHub Security Advisories rather than posting exploit details in a public issue. Include affected versions, reproduction steps, impact, and any suggested mitigation. Avoid including API keys, chat history, or personal data in a report.

Omni is experimental software that can access sensitive information and invoke device actions. Review permissions and confirmations before enabling device control. The Windows desktop stores provider credentials in a local `secrets.json` file; it is not currently protected by an operating-system credential vault. Use a dedicated account and limited credentials until this is improved.
