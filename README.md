# ui-chime-vol

A private Kotlin/Ktor API for changing UniFi Protect doorbell ring volume from Apple Shortcuts. It targets two configured chimes with the aliases `upstairs` and `downstairs`, uses Ubiquiti's official cloud connector, and does not require access to the home LAN.

Only `ringSettings[].volume` is changed. The separate PoE chime Sound/master volume, doorbell speaker volume, and mechanical chimes are not controlled.

## Prerequisites

- UniFi OS console firmware 5.0.3 or later, with remote management and cloud connectivity working.
- UniFi Protect chimes paired to one or more doorbells.
- A UniFi API key from [UniFi Site Manager](https://unifi.ui.com/) under **Settings → API Keys**. Treat this key as a sensitive credential; Ubiquiti does not document chime-only permissions.
- The console ID from Site Manager.

The pinned project wrapper uses Kotlin Toolchain 0.13.0. Its current defaults provide Kotlin 2.4.20, Ktor 3.6.0, kotlinx.serialization 1.11.0, and JUnit 6.1.3. Builds target Java 25 LTS. No global Kotlin, Gradle, or JDK installation is required.

## Discover chime IDs

Set only the connection variables, then run the read-only discovery mode:

```sh
export UNIFI_API_KEY='your-api-key'
export UNIFI_CONSOLE_ID='your-console-id'
./kotlin run -- discover-chimes
```

The JSON output lists each chime's ID, name, and doorbell camera IDs with current volumes. Copy the desired chime IDs into `CHIME_UPSTAIRS_ID` and `CHIME_DOWNSTAIRS_ID`. Discovery does not require the service token or chime configuration and never writes settings.

## Configuration

| Variable | Required | Default | Purpose |
| --- | --- | --- | --- |
| `SERVICE_BEARER_TOKEN` | Yes | — | Random token of at least 32 characters; required by both volume endpoints |
| `UNIFI_API_KEY` | Yes | — | UniFi API key sent only to the configured UniFi API |
| `UNIFI_CONSOLE_ID` | Yes | — | Console used by the official cloud connector |
| `CHIME_UPSTAIRS_ID` | Yes | — | Stable ID reported by discovery |
| `CHIME_DOWNSTAIRS_ID` | Yes | — | Stable ID reported by discovery; must differ from upstairs |
| `VOLUME_QUIET` | No | `0` | Ring volume for the quiet preset, from 0 through 100 |
| `VOLUME_NORMAL` | No | `50` | Ring volume for the normal preset, from 0 through 100 |
| `PORT` | No | `8080` | HTTP listening port; Railway supplies this automatically |
| `UNIFI_TIMEOUT_MS` | No | `30000` | Timeout for each UniFi request |
| `UNIFI_BASE_URL` | No | Official connector URL | Full Protect integration base URL, primarily for advanced testing |

`.env.example` contains placeholders, not usable credentials. The application does not automatically load `.env` files. Missing or invalid configuration prevents server startup. Startup does not call UniFi.

Generate a suitable service token with:

```sh
openssl rand -hex 32
```

## Run and test locally

```sh
./kotlin test
./kotlin package -f executable-jar
```

With all required environment variables set:

```sh
./kotlin run
```

Or run the packaged application with Java 25:

```sh
java -jar build/tasks/_ui-chime-vol_executableJarJvm/ui-chime-vol-jvm-executable.jar
```

## HTTP API

`GET /health` is public and returns `{"status":"ok"}`. It reports process health, not UniFi availability.

Both volume endpoints require:

```text
Authorization: Bearer <SERVICE_BEARER_TOKEN>
```

### Set volume

`PUT /v1/chimes/volume` with a JSON body:

```json
{"preset":"quiet","target":"upstairs"}
```

`preset` is `quiet` or `normal`. `target` is `upstairs`, `downstairs`, or `all`; omitting it means `all`.

```sh
curl --fail-with-body "https://your-service.up.railway.app/v1/chimes/volume" \
  -X PUT \
  -H "Authorization: Bearer $SERVICE_BEARER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"preset":"quiet","target":"upstairs"}'
```

For each selected chime, the service reads current settings, preserves every other ring-setting field, updates every associated doorbell pairing, skips a write if all volumes already match, and reads again to verify a change. Per-chime results report `changed`, `unchanged`, or `failed` and include resulting camera IDs and volumes when available.

### Read volume

`GET /v1/chimes/volume` accepts the same optional target as a query parameter:

```sh
curl --fail-with-body "https://your-service.up.railway.app/v1/chimes/volume?target=downstairs" \
  -H "Authorization: Bearer $SERVICE_BEARER_TOKEN"
```

Omit `target` to read both chimes.

### Errors

Errors have a stable code and message:

```json
{"error":{"code":"invalid_target","message":"Target must be upstairs, downstairs, or all"}}
```

| HTTP status | Meaning |
| --- | --- |
| `400` | Invalid JSON, preset, or target |
| `401` | Missing or incorrect bearer token |
| `502` | UniFi rejection, unavailable upstream, verification failure, or partial device failure |
| `504` | UniFi timeout; after a PUT, the outcome is unknown and may need a GET to establish current state |

Mutation and read failures that involve chime results include a top-level `results` array. The service attempts every selected chime and does not roll back successful changes when another chime fails. It never automatically retries a mutation.

## Apple Shortcuts

Create one reusable Shortcut named **Chime Volume**, then duplicate it for common actions:

1. Add a **Text** action containing the Railway HTTPS base URL without a trailing slash.
2. Add a **Text** action containing the service bearer token.
3. Add a **Dictionary** action with `preset` and `target` values. Valid examples are `quiet` / `upstairs`, `normal` / `downstairs`, and `quiet` / `all`.
4. Add **Get Contents of URL**. Use the base URL plus `/v1/chimes/volume`, method `PUT`, header `Authorization` set to `Bearer ` followed by the token text, and a JSON request body using the dictionary.
5. Add **Show Result** or **Show Notification** for the response. Configure the Shortcut's failure path to show the returned error message; do not add an automatic repeat or retry.
6. Duplicate the Shortcut and change the dictionary for other actions, or invoke it from small wrappers such as **Quiet Upstairs** and **Normal House**.

The token is stored in the Shortcut. Do not share a configured Shortcut without removing it. Allow approximately 45 seconds for a request so the Shortcut outlives the service's 30-second upstream timeout.

## Railway deployment

1. Push this project to a repository and create a Railway service from it. `railway.json` selects the Dockerfile and configures `/health` as the health check.
2. Set all required variables from the table as Railway variables/secrets. Do not commit real values.
3. Deploy one instance and use the generated Railway HTTPS domain. No custom domain, database, VPN, or inbound home-network rule is needed.
4. Verify `/health`, then use authenticated GET and PUT requests as above.

To build the exact deployment artifact locally:

```sh
docker build -t ui-chime-vol .
```

## Credential rotation

For the service token, generate a replacement, update the Railway variable, update every configured Shortcut, redeploy, and verify all common actions. For the UniFi key, create a replacement in Site Manager, update Railway, redeploy and verify, then revoke the old key. Never send either credential in logs, issue reports, or shared Shortcuts.

## Real-console verification

Automated tests use mocked UniFi responses. Before relying on the service:

1. Run discovery and confirm both configured IDs map to the intended physical chimes.
2. Read both volumes through the service and compare them with Protect.
3. At a suitable time, apply `quiet` to `upstairs`, confirm the GET result is 0, and perform a controlled doorbell-ring test.
4. Apply `normal` and confirm the configured volume returns.
5. Repeat for `downstairs`, then test `all` and verify the Shortcut error display with an intentionally invalid target.

A quiet value of 0 silences the configured chime ring. Do not treat this service as the only safeguard for a sleeping child until the physical test has passed.
