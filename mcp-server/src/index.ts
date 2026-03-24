#!/usr/bin/env node

import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";

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

async function blipFetch(
  path: string,
  options: RequestInit = {}
): Promise<unknown> {
  const url = `${API_URL}${path}`;
  const res = await fetch(url, {
    ...options,
    headers: {
      Authorization: `Bearer ${API_KEY}`,
      "Content-Type": "application/json",
      ...options.headers,
    },
  });

  if (!res.ok) {
    const body = await res.text();
    throw new Error(`Blip API error ${res.status} on ${options.method || "GET"} ${path}: ${body}`);
  }

  if (res.status === 204) return null;
  return res.json();
}

const server = new McpServer({
  name: "blip",
  version: "0.1.2",
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
    const interval = 2000;

    while (Date.now() < deadline) {
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

      await new Promise((resolve) => setTimeout(resolve, interval));
    }

    return {
      content: [
        {
          type: "text",
          text: `No email received in inbox ${inbox_id} after ${timeout} seconds.`,
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
