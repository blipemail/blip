# Contributing to blip

Thanks for your interest in contributing! This guide will help you get set up.

## Prerequisites

- JDK 21+
- [libSQL (sqld)](https://github.com/tursodatabase/libsql) for the local database
- Node.js 20+ (for MCP server and workers)

## Getting started

```bash
# Clone the repo
git clone https://github.com/blipemail/blip.git
cd blip

# Start a local libSQL instance
sqld --http-listen-addr 127.0.0.1:8081 &

# Build and run the server
./gradlew :server:core:buildFatJar
TURSO_URL=http://localhost:8081 WORKER_SECRET=dev-secret \
  java -jar server/core/build/libs/*-all.jar
```

The server runs on `http://localhost:8080`. Migrations run automatically on startup.

## Project structure

| Module | Language | Description |
|--------|----------|-------------|
| `server/core` | Kotlin/Ktor | API server — inboxes, emails, webhooks, SSE, forwarding |
| `cli` | Kotlin/Clikt | Terminal client for blip |
| `mcp-server` | TypeScript | MCP server for AI agent integration |
| `workers` | TypeScript | Cloudflare Workers for email ingress |
| `shared-models` | Kotlin | Data models shared between server and CLI |

## Running tests

```bash
# All server tests
./gradlew :server:core:test

# Specific test class
./gradlew :server:core:test --tests "dev.bmcreations.blip.server.services.EmailServiceTest"
```

## Making changes

1. Fork the repo and create a branch from `main`
2. Make your changes
3. Add or update tests as needed
4. Run `./gradlew :server:core:test` and ensure all tests pass
5. Open a pull request

## Code style

- Kotlin: follow the existing code conventions (standard Kotlin style)
- TypeScript: follow the existing code conventions
- Keep commits focused — one logical change per commit

## Reporting issues

Open an issue at [github.com/blipemail/blip/issues](https://github.com/blipemail/blip/issues).

## License

By contributing, you agree that your contributions will be licensed under AGPL-3.0.
