# blip workers

Cloudflare Workers for Blip email infrastructure.

## email-ingress

Cloudflare Email Worker that receives inbound emails and forwards them to the Blip API server. Uses Cloudflare Email Routing to catch-all emails for configured domains.

### How it works

1. Cloudflare Email Routing receives an email to `*@bl1p.dev` (or other configured domains)
2. The worker parses the raw email using `postal-mime`
3. Extracts body, headers, and base64-encodes attachments
4. POSTs the parsed email to `POST /v1/inboxes/{address}/emails` on the API server
5. Authenticates with the API via `X-Worker-Secret` header

### Configuration

Set via `wrangler.toml` and Wrangler secrets:

| Variable | Location | Description |
|----------|----------|-------------|
| `API_BASE_URL` | `wrangler.toml` | API server base URL |
| `WORKER_SECRET` | `wrangler secret` | Shared secret matching the server's `WORKER_SECRET` |

### Deploy

```bash
cd workers/email-ingress
wrangler deploy
wrangler secret put WORKER_SECRET
```

## License

AGPL-3.0
