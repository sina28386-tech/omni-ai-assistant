import { HttpError } from "./errors.js";
import { readWorkspaceFile, type WorkspaceFile } from "./workspaces.js";

/** Workspace authority is supplied by the caller, never by model arguments. */
export interface ToolContext { workspaceId: string | null; signal?: AbortSignal }
export interface WorkspaceReadInput { path: string }
export type WorkspaceReadOutput = WorkspaceFile;

export const workspaceReadSpec = {
  name: "workspace_read",
  description: "Read a UTF-8 text file in the selected project. Paths are relative.",
  parameters: { type: "object", properties: { path: { type: "string" } }, required: ["path"], additionalProperties: false },
} as const;

export const toolRegistry = { workspace_read: workspaceReadSpec } as const;

export async function executeTool(name: string, input: unknown, context: ToolContext): Promise<WorkspaceReadOutput> {
  if (name !== workspaceReadSpec.name) throw new HttpError(400, "tool_unavailable", "This tool is not available.");
  if (!context.workspaceId) throw new HttpError(403, "workspace_required", "Select a project first.");
  if (!input || typeof input !== "object" || Array.isArray(input) ||
      Object.keys(input).length !== 1 || typeof (input as Record<string, unknown>).path !== "string") {
    throw new HttpError(400, "tool_arguments_invalid", "Provide a project file path.");
  }
  context.signal?.throwIfAborted();
  const result = await readWorkspaceFile(context.workspaceId, (input as WorkspaceReadInput).path);
  context.signal?.throwIfAborted();
  return result;
}
