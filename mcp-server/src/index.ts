#!/usr/bin/env node

import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";
import { createRequire } from "node:module";

// dist/index.js -> ../package.json
const pkg = createRequire(import.meta.url)("../package.json") as { version: string };

const API_URL = process.env.BLIP_API_URL || "https://api.useblip.email";
const API_KEY = process.env.BLIP_API_KEY || "";

if (!API_KEY) {
  console.error(
    "BLIP_API_KEY is required. Create one at https://useblip.email/app"
  );
  process.exit(1);
}

if (!/^blip_[a-zA-Z0-9_]+$/.test(API_KEY)) {
  console.error(
    "BLIP_API_KEY has an invalid format. Keys start with 'blip_' followed by alphanumeric characters."
  );
  process.exit(1);
}

class BlipApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly retryAfterSeconds?: number
  ) {
    super(message);
    this.name = "BlipApiError";
  }
}

function errorDetail(body: string): string {
  try {
    const parsed = JSON.parse(body) as { message?: unknown };
    if (typeof parsed.message === "string" && parsed.message) return parsed.message;
  } catch {
    // not JSON
  }
  return body.trim();
}

function parseRetryAfter(value: string | null): number | undefined {
  if (!value) return undefined;
  const seconds = Number(value);
  if (Number.isFinite(seconds) && seconds >= 0) return seconds;
  const date = Date.parse(value);
  if (!Number.isNaN(date)) return Math.max(0, Math.ceil((date - Date.now()) / 1000));
  return undefined;
}

function describeError(
  status: number,
  method: string,
  path: string,
  detail: string,
  retryAfter?: number
): string {
  const upgrade =
    "This feature needs a higher Blip plan. Upgrade at https://useblip.email/app.";
  const invalidKey =
    "Invalid or expired API key. Check BLIP_API_KEY, or create a new key at https://useblip.email/app.";
  switch (status) {
    case 401:
      return invalidKey;
    case 402:
      return `${upgrade}${detail ? ` (${detail})` : ""}`;
    case 403:
      // The server also uses 403 for "Access denied" on resources the key doesn't own.
      return /plan|tier|upgrade|pro\b|agent/i.test(detail)
        ? `${upgrade}${detail ? ` (${detail})` : ""}`
        : `Access denied${detail ? ` (${detail})` : ""}. The API key may be invalid or expired, or it doesn't own this resource.`;
    case 429:
      return retryAfter !== undefined
        ? `Rate limited by the Blip API. Retry after ${retryAfter} seconds.`
        : "Rate limited by the Blip API. Wait a moment and retry.";
    default:
      return `Blip API error ${status} on ${method} ${path}: ${detail}`;
  }
}

async function blipFetch(
  path: string,
  options: RequestInit = {}
): Promise<unknown> {
  const url = `${API_URL}${path}`;
  const method = options.method || "GET";
  const res = await fetch(url, {
    ...options,
    headers: {
      Authorization: `Bearer ${API_KEY}`,
      "Content-Type": "application/json",
      ...options.headers,
    },
  });

  if (!res.ok) {
    const detail = errorDetail(await res.text());
    const retryAfter = parseRetryAfter(res.headers.get("Retry-After"));
    throw new BlipApiError(
      describeError(res.status, method, path, detail, retryAfter),
      res.status,
      retryAfter
    );
  }

  if (res.status === 204) return null;
  return res.json();
}

// 5xx, 429 and network failures (fetch rejects) may succeed on a later attempt.
function isTransient(err: unknown): boolean {
  if (err instanceof BlipApiError) return err.status === 429 || err.status >= 500;
  return err instanceof TypeError;
}

const server = new McpServer({
  name: "blip",
  version: pkg.version,
});

// --- Tools ---

server.tool(
  "create_inbox",
  "Create a new disposable email inbox. Returns the inbox ID and email address.",
  {
    slug: z
      .string()
      .optional()
      .describe("Custom address slug (e.g. 'mytest' for mytest@useblip.email)"),
    domain: z
      .string()
      .optional()
      .describe("Email domain (defaults to useblip.email)"),
    ttl_minutes: z
      .number()
      .optional()
      .describe(
        "How long the inbox should live, in minutes (AGENT tier only, max 90 days). Defaults to 60 minutes if omitted."
      ),
  },
  async ({ slug, domain, ttl_minutes }) => {
    const body: Record<string, unknown> = {};
    if (slug) body.slug = slug;
    if (domain) body.domain = domain;
    if (ttl_minutes !== undefined) body.windowMinutes = ttl_minutes;

    const result = await blipFetch("/v1/inboxes", {
      method: "POST",
      body: JSON.stringify(body),
    });

    return { content: [{ type: "text", text: JSON.stringify(result, null, 2) }] };
  }
);

server.tool(
  "list_inboxes",
  "List all active inboxes for the current API key.",
  {},
  async () => {
    const result = await blipFetch("/v1/inboxes");
    return { content: [{ type: "text", text: JSON.stringify(result, null, 2) }] };
  }
);

server.tool(
  "get_inbox",
  "Get inbox details and list of received emails.",
  {
    inbox_id: z.string().describe("The inbox ID"),
  },
  async ({ inbox_id }) => {
    const result = await blipFetch(`/v1/inboxes/${inbox_id}`);
    return { content: [{ type: "text", text: JSON.stringify(result, null, 2) }] };
  }
);

server.tool(
  "read_email",
  "Read the full content of a specific email including body, headers, and attachments.",
  {
    email_id: z.string().describe("The email ID to read"),
  },
  async ({ email_id }) => {
    const result = await blipFetch(`/v1/emails/${email_id}`);
    return { content: [{ type: "text", text: JSON.stringify(result, null, 2) }] };
  }
);

server.tool(
  "extract_codes",
  "Extract OTP codes and verification links from the most recent email in an inbox. Use this after creating an inbox and receiving a verification/signup email.",
  {
    inbox_id: z
      .string()
      .describe("The inbox ID to extract codes from (uses most recent email)"),
  },
  async ({ inbox_id }) => {
    const result = await blipFetch(`/v1/inboxes/${inbox_id}/extract`);
    return { content: [{ type: "text", text: JSON.stringify(result, null, 2) }] };
  }
);

server.tool(
  "wait_for_email",
  "Poll an inbox until an email arrives. Returns the email once received. Times out after the specified duration.",
  {
    inbox_id: z.string().describe("The inbox ID to wait on"),
    timeout_seconds: z
      .number()
      .optional()
      .describe("Max seconds to wait (default: 60, max: 300)"),
  },
  async ({ inbox_id, timeout_seconds }) => {
    const timeout = Math.min(timeout_seconds ?? 60, 300);
    const deadline = Date.now() + timeout * 1000;
    const baseDelay = 2000;
    const maxDelay = 10000;
    let failures = 0;
    let lastError: unknown;

    while (Date.now() < deadline) {
      let delay = baseDelay;
      try {
        const result = (await blipFetch(`/v1/inboxes/${inbox_id}`)) as {
          emails?: { id: string }[];
        };

        if (result?.emails && result.emails.length > 0) {
          // Return the full detail of the most recent email
          const email = await blipFetch(`/v1/emails/${result.emails[0].id}`);
          return {
            content: [{ type: "text", text: JSON.stringify(email, null, 2) }],
          };
        }
        failures = 0;
      } catch (err) {
        if (!isTransient(err)) throw err;
        lastError = err;
        failures++;
        delay = Math.min(baseDelay * 2 ** (failures - 1), maxDelay);
        if (err instanceof BlipApiError && err.retryAfterSeconds !== undefined) {
          delay = Math.max(delay, err.retryAfterSeconds * 1000);
        }
      }

      const remaining = deadline - Date.now();
      if (remaining <= 0) break;
      await new Promise((resolve) => setTimeout(resolve, Math.min(delay, remaining)));
    }

    return {
      content: [
        {
          type: "text",
          text:
            `No email received in inbox ${inbox_id} after ${timeout} seconds.` +
            (lastError
              ? ` The last poll failed with a transient error: ${(lastError as Error).message}`
              : ""),
        },
      ],
    };
  }
);

server.tool(
  "delete_inbox",
  "Delete an inbox and all its emails.",
  {
    inbox_id: z.string().describe("The inbox ID to delete"),
  },
  async ({ inbox_id }) => {
    await blipFetch(`/v1/inboxes/${inbox_id}`, { method: "DELETE" });
    return {
      content: [{ type: "text", text: `Inbox ${inbox_id} deleted.` }],
    };
  }
);

// --- Start ---

async function main() {
  const transport = new StdioServerTransport();
  await server.connect(transport);
}

main().catch((err) => {
  console.error("Fatal error:", err);
  process.exit(1);
});
