# @useblip/email

MCP server for [Blip](https://useblip.email) disposable email. Create inboxes, receive emails, and extract OTP codes — all from your AI agent.

## Setup

### Claude Desktop

Add to your `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "blip": {
      "command": "npx",
      "args": ["-y", "@useblip/email"],
      "env": {
        "BLIP_API_KEY": "blip_ak_..."
      }
    }
  }
}
```

### Claude Code

```bash
claude mcp add blip -- npx -y @useblip/email
```

Set your API key:

```bash
export BLIP_API_KEY=blip_ak_...
```

### Other MCP clients

Run the server directly:

```bash
BLIP_API_KEY=blip_ak_... npx @useblip/email
```

## Getting an API key

1. Sign in at [app.useblip.email](https://app.useblip.email)
2. Subscribe to the **Agent** tier
3. Create an API key from the dashboard

## Tools

| Tool | Description |
|------|-------------|
| `create_inbox` | Create a disposable email inbox with optional custom slug, domain, and TTL |
| `list_inboxes` | List all active inboxes |
| `get_inbox` | Get inbox details and list of received emails |
| `read_email` | Read full email content (body, headers, attachments) |
| `extract_codes` | Extract OTP codes and verification links from the latest email |
| `wait_for_email` | Poll until an email arrives (configurable timeout, default 60s) |
| `delete_inbox` | Delete an inbox and all its emails |

## Example prompts

- "Create a disposable email and sign up for example.com, then give me the verification code"
- "Make an inbox, wait for the password reset email, and extract the link"
- "List my active inboxes and show me the latest emails"

## Environment variables

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `BLIP_API_KEY` | Yes | — | Your Blip API key |
| `BLIP_API_URL` | No | `https://api.useblip.email` | API base URL (for self-hosted) |

## License

[MIT](LICENSE)
