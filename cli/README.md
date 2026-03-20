# blip CLI

Manage disposable email inboxes from your terminal. Watch for emails in real-time, read content, and extract OTPs without leaving your workflow.

## Install

```bash
brew install bmcreations/tap/blip
```

Or build from source:

```bash
./gradlew :cli:installDist
./cli/build/install/cli/bin/cli --help
```

## Commands

### `blip create`

Create a new disposable inbox.

```bash
blip create
blip create --slug my-test        # Custom address (PRO)
blip create --sniper 30           # Sniper window in minutes (PRO)
blip create --domain useblip.email
```

### `blip inbox`

List inboxes or view a specific inbox.

```bash
blip inbox                              # List all
blip inbox swift-fox-42@bl1p.dev        # View specific inbox
blip inbox swift-fox-42@bl1p.dev --watch  # Real-time monitoring
```

In watch mode:
- Type a number to read that email
- Type `<number> reply` to reply interactively
- `/list` to refresh the email list
- `/help` for commands
- `/quit` to exit

### `blip read <email-id>`

Display a specific email's full content.

```bash
blip read email-abc123
```

### `blip login`

Sign in to your Blip account via device code flow. Opens your browser for authentication.

```bash
blip login
```

### `blip logout`

Clear stored session token.

### `blip whoami`

Show current session tier and user info.

### `blip open`

Open the Blip web app in your browser with your current session.

### `blip billing`

Open the Stripe billing portal to manage your subscription.

## Configuration

Config is stored at `~/.config/blip/config.json`.

| Environment variable | Default | Description |
|---------------------|---------|-------------|
| `BLIP_API_URL` | `https://api.useblip.email` | API base URL |
| `BLIP_FRONTEND_URL` | `https://app.useblip.email` | Frontend URL for `blip open` |

## License

AGPL-3.0
