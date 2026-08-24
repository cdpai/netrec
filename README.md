# netrec — Chrome/CDP network recorder for agentic workflows

`netrec` attaches to a Chrome/Vivaldi tab you are driving manually (via the DevTools Protocol), records the network traffic into a local ArcadeDB store, and exposes it through a CLI so an AI agent (Claude Code) can see exactly what a click sent and synthesize replay code. It is a CLI alternative to an MCP server.

## Shape: one daemon, thin commands

There is a single persistent daemon. It holds the DB, owns the recorder, and serves a localhost control API on `127.0.0.1:2477`.

**Every** command — `rec` included — is a thin, fast client of that API, and starts the daemon on demand (ssh-agent style). So:

- `netrec rec` **returns immediately**; recording outlives the command. You are never locked to a process you started.
- A failed tab match costs you one command, never the daemon.
- `netrec stop` shuts down gracefully, so the next start has no "was not closed properly" recovery.
- Two named sessions can record two different tabs at once.

`netrec serve` runs that daemon in the foreground if you want to watch it.

## Build

```
netrec stop              # a running daemon holds the jar open, so clean would fail
mvn clean package        # produces target/netrec.jar (shaded uber-jar)
```

Installed as a global command: `netrec.exe` (a jr.exe launcher) + `netrec.jrc` in `cmdtools`, pointing at `target/netrec.jar`. Rebuild updates it in place. (Plain alternative: `java -jar target/netrec.jar <args>`.)

## Quickstart

1. `netrec config --detect` — probes localhost and reports any browser already listening for CDP (`--adopt` saves the port it found). Plain `netrec config` shows the settings and the launch command. The port is randomized per install, deliberately not the well-known 9222.
2. Launch the browser with that port (logged in, on the page you care about):
   ```
   vivaldi.exe --remote-debugging-port=<PORT>       (or chrome.exe)
   ```
3. `netrec tabs` — see the attachable targets.
4. `netrec rec --tab <substring> -s assign` — attaches and returns. Non-zero exit unless attachment was **confirmed**, so "go ahead, do the thing" is never said over a dead recorder.
5. `netrec mark "about to hit Save"` — stamp the timeline, then perform the action in the browser.
6. Read it back:
   ```
   netrec ls --api --since-mark                 # XHR+Fetch since that mark
   netrec ls --writes --since 5m                # what did that click actually send?
   netrec show <id> --req                       # request side only
   netrec curl <id> --reveal                    # ready-to-run replay with real auth
   ```
7. `netrec rec stop -s assign`, and `netrec stop` when done.

## Selection and projection are separate jobs

`ls` answers **which records**. `show` answers **which parts of a record**. Both reductions happen *inside the daemon*, before anything is serialized — a downstream `| jq` is too late when one response body is 182 KB.

Selection (`ls`):

- `-s/--session`, `--host`, `-u/--url`, `--since 5m`, `--since-mark [label]`, `-n`
- `-m/--method POST,PATCH` and `-T/--type XHR,Fetch` take comma lists
- `--status 404`, `--status 4xx`, `--status 200,404`
- `--writes` = POST,PUT,PATCH,DELETE · `--api` = XHR,Fetch (drops the `_next/static` and beacon noise)

Projection (`show`, and `ls` too):

- `--req` / `--resp` / `--body req|resp|both|none`, `--body-head <N>`, `--fields a,b,c`
- `--jq '<expr>'` — a real jq expression, evaluated in the daemon

JSON bodies are exposed **already parsed** as `.req` and `.resp`, so reaching through a body is one expression, not a select-then-reparse cascade:

```
netrec show <id> --jq '{count: .resp.count, names: [.resp.results[].name][0:5]}'
netrec ls --url unassigned --jq '{url, n: .resp.count}'      # applied per record -> JSONL
```

`ls --jq` emits one JSON document per line, so `show` is just the single-record case of `ls`.

## Capture scope — and what it misses

When you attach, netrec prints exactly what it attached to. Trust nothing it did not claim to see.

- **Page + children.** Iframes (including cross-origin OOPIFs), dedicated workers and service workers are auto-attached as they appear, so service-worker-routed XHR is captured. `--no-children` turns that off. Each record carries `target` / `targetType` so you can tell where it came from.
- **Moving targets.** A url/title substring stays a live subscription: tabs that open, or navigate into the pattern later, are adopted automatically. `--all` records every http page. `--wait <seconds>` sits until a matching tab appears instead of failing.
- **Miss:** the opening burst of a *brand-new* tab, if adoption loses the race — its initial document, and sometimes the first XHR after it. Everything from the moment it is attached is complete. Attach to the tab that is already open, then act.
- **Miss:** a numeric `--tab` is treated as an index from `tabs` first, and only falls back to substring matching if that index is out of range.
- **Write-on-complete.** Records land when the response finishes (or fails); `state` is `pending`/`complete`/`failed`.
- **Redirects** reuse the CDP request id — only the final hop is kept.
- Bodies are capped at `--max-body` (2 MB per side); truncation is flagged in the record.

## Sessions, marks, lifecycle

```
netrec sessions                    # named captures with counts and time span
netrec marks                       # timeline marks, newest first
netrec clear -s <session> --yes    # prune a capture you are finished with
netrec status                      # daemon up? recording what? how much stored? (never autostarts)
netrec stop                        # graceful shutdown
netrec serve [--idle <minutes>]    # foreground daemon; autostarted ones idle out after 8h
```

`NETREC_NO_AUTOSTART=1` disables on-demand starting. `NETREC_DEBUG=1` logs CDP target events. Daemon output goes to `~/littlejlib/netrec/daemon.log`.

## Cookies — read the jar, don't wait for traffic

```
netrec cookies --url portal.example.com                 # what is held, and when it expires
netrec cookies --url portal.example.com --reveal        # with real values
netrec cookies --url portal.example.com --name _oauth2_proxy --value-only
```

Recording answers *what did the browser send*; this answers *what is the browser holding*. They are different questions, and the difference is a human's time: a session cookie only shows up in captured traffic if a request happens to go out while the recorder is attached, so recovering an expired auth cookie used to mean asking whoever is at the keyboard to reload a page. Reading the jar over `Storage.getCookies` needs no traffic at all.

It also reports **expiry**, which is normally the answer to "why did my tool start getting 401s":

```
_oauth2_proxy   .example.com   2026-08-15 18:54:45   in 116h32m   <redacted len=148>
```

Filters: `--url <host|url>` (cookie-jar host matching, so a `.domain` cookie matches its subdomains), `--domain <substr>`, `--name <substr>`. Values are masked unless `--reveal`. `--value-only` prints the bare value and nothing else — it implies `--reveal` and **fails unless exactly one cookie matches**, so a script cannot silently grab the wrong one:

```
mytool auth import --from-netrec       # a downstream tool calls exactly that, then encrypts it
```

The connection is borrowed from the recorder when one is already open on that port, otherwise it is opened and closed again — asking for a cookie never leaves a connection behind and never disturbs a recording in progress.

## Secrets

The store lives at `~/littlejlib/netrec/databases/netrec` and contains **live cookies / auth tokens**. Output masks `Cookie`/`Authorization`/token headers by default — the masking happens in the daemon, so secrets do not cross to stdout at all unless you pass `--reveal` (on `show` / `curl` / `ls`). Cookies rotate quickly: regenerate a `curl --reveal` right before running it.

Getting them off the disk again is `clear`, which is a **dry run unless you pass `--yes`**:

```
netrec clear -s assign                     # what would go (nothing is touched)
netrec clear -s assign --yes --marks       # delete that capture and its marks
netrec clear --older-than 7d --yes         # retention sweep
netrec clear --host portal.example --yes
netrec clear --all --yes                   # drop the database files and start empty
```

Selective deletes are **logical**: rows go, but the bytes can sit in the ArcadeDB bucket until compaction, so `clear` says so rather than implying a shred. `--all` drops and recreates the database files, which is the one path that really removes them — it verifies afterwards that nothing pre-dating the wipe is left on disk, and warns with a byte count if something is. It refuses while anything is recording, and `clear -s X` refuses if `X` is the session being recorded.

## APIs (for chaining without a JVM spawn)

- **ctl API** — `http://127.0.0.1:2477`, localhost only, JSON in/out: `POST /ping`, `/q` (`{sql, params, jq, fields, body, bodyHead, reveal, raw, parse}`), `/rec/start`, `/rec/stop`, `/rec/status`, `/cookies` (`{url, domain, name, reveal, port}`), `/mark`, `/shutdown`.
- **ArcadeDB hub** — `http://127.0.0.1:<hub port>` (`root` / `netrec_local`), the raw SQL escape hatch and Studio UI. Types: `ReqRec`, `Mark`. The port is the first free one in `2480-2489`, because every other ArcadeDB app on the machine also defaults to 2480 and this endpoint is not worth failing a daemon start over. Ask for the port actually bound — `netrec status`, `netrec config`, or `hubPort` from `POST /ping` — rather than assuming 2480. Pin it with `netrec config --hub-port 2600-2609` (persisted), `netrec serve --port 2600` (one run), or `NETREC_HUB_PORT` (one process).

## Still missing

- Exports: `curl` only (HAR / Playwright / fetch planned).
- `clear` has no `--keep-last N`, no compaction, and no automatic retention policy — pruning is always something you ask for.
- `tag` / `focus` verbs and the Javalin+luvml live dashboard are still planned (see `prp/01-prp.03.opus-deep-study.md`).
- Config is still `config.properties`; the move to `netrec_config.json` plus browser-exe registration is PRP 02.
