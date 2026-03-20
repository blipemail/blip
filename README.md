# blip

Instant disposable email inboxes. API, CLI, MCP server, and web app.

**[useblip.email](https://useblip.email)** | **[Docs](https://useblip.email/docs)** | **[API Reference](https://useblip.email/docs/api)**

## Features

- Disposable email addresses with random or custom slugs
- Real-time email delivery via SSE
- OTP and verification link extraction
- Webhooks with HMAC-SHA256 signatures and delivery logs
- CLI for terminal workflows
- MCP server for AI agents
- Multi-domain support

## Architecture

```
blip/
  server/core/      Kotlin/Ktor API server (AGPL-3.0)
  cli/              Kotlin/Clikt CLI
  mcp-server/       TypeScript MCP server (npm: @useblip/email)
  shared-models/    Shared Kotlin data models
```

## Quickstart

### Managed (useblip.email)

No setup required. Create inboxes at [app.useblip.email](https://app.useblip.email) or via the API:

```bash
# Get a session token
curl -X POST https://api.useblip.email/v1/sessions

# Create an inbox
curl -X POST https://api.useblip.email/v1/inboxes \
  -H "Authorization: Bearer <token>"
```

### CLI

```bash
brew install bmcreations/tap/blip

blip create
blip inbox --watch
```

### Self-hosted

Prerequisites: JDK 21+, [libSQL (sqld)](https://github.com/tursodatabase/libsql)

```bash
git clone https://github.com/blipemail/blip.git
cd blip
./gradlew :server:core:buildFatJar

# Start libSQL
sqld --http-listen-addr 127.0.0.1:8081 &

# Run the server
TURSO_URL=http://localhost:8081 WORKER_SECRET=dev-secret \
  java -jar server/core/build/libs/*-all.jar
```

Or with Docker:

```bash
docker build -t blip .
docker run -p 8080:8080 \
  -e TURSO_URL=http://host.docker.internal:8081 \
  -e WORKER_SECRET=your-secret \
  blip
```

## Environment variables

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `PORT` | No | `8080` | Server port |
| `TURSO_URL` | Yes | `http://localhost:8081` | libSQL/Turso database URL |
| `TURSO_AUTH_TOKEN` | No | — | Turso auth token (production) |
| `WORKER_SECRET` | Yes | `dev-secret` | Shared secret for authenticating inbound email delivery |
| `FRONTEND_URL` | No | `http://localhost:4321` | Frontend URL for CORS |

## Development

See [CONTRIBUTING.md](CONTRIBUTING.md) for development setup and contribution guidelines.

```bash
# Run tests
./gradlew :server:core:test

# Build CLI
./gradlew :cli:installDist
./cli/build/install/cli/bin/cli --help

# Build MCP server
cd mcp-server && npm install && npm run build
```

## License

AGPL-3.0. See [LICENSE](LICENSE).
