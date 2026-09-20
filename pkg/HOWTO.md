# netrec — how to use it

## What it is, and what it is for

You are logged into some web application in your browser. You click a button, and the page saves something. Somewhere between the click and the save, the browser sent an HTTP request with a URL, headers, a session cookie and a JSON body — and if you knew exactly what that request was, you could script it, replay it, or hand it to an AI agent and have it write the automation for you. netrec is the thing that tells you exactly what that request was.

It attaches to a tab you are driving by hand, records every request and response that tab makes into a local database, and gives you a command line to read it back. Nothing is uploaded anywhere. The browser stays yours: you are logged in, you click the things, netrec just watches.

The reason it exists in this shape — a command line rather than a browser extension or a devtools panel — is that an AI coding agent can run a command and read its output, whereas it cannot look at your screen. So `netrec ls --writes --since 2m` is a question an agent can ask and get an exact answer to.

## Step 1 — install

Unzip this folder anywhere you like (your home directory is fine; it does not need Program Files) and double-click `install.cmd`. It checks that you have a JDK, points `netrec.exe` at the jar next to it, and adds the folder to your PATH. It needs no administrator rights and it changes nothing else on the machine.

Then open a NEW terminal — the one you already had open does not know about the PATH change — and check it:

```
netrec --help
```

If you would rather not touch PATH at all, skip `install.cmd` and run it as `netrec.exe` from inside this folder, or as `java -jar netrec.jar` from anywhere. Everything below works the same.

The first run is slow, perhaps half a minute, because it builds a startup cache next to the jar. Every run after that is quick. This is normal and it happens once.

## Step 2 — start the browser so it can be recorded

Double-click **"Start browser for netrec"** on your desktop. That is the whole step.

`install.cmd` put that shortcut there; it runs `start-browser-debug.cmd` from this folder, which you can also run directly. It opens Vivaldi (or Chrome, or Edge) with the recording port switched on, and tells you when the port is actually answering.

It is worth knowing why this needs a shortcut at all, because it is the one thing that catches everybody. A browser can only be recorded if it was told to listen for the DevTools Protocol **at the moment it started** — and a browser that is already running ignores being told later. Start Vivaldi normally, then run the command with the port, and all that happens is your existing Vivaldi opens another window: no port, no error message, and netrec simply sees nothing. So the browser has to be closed completely and started again. The script notices this for you, offers to close it, and reopens it with the same profile so your tabs and your logins are all still there.

The port itself is a random number chosen when netrec was installed, deliberately not the well-known 9222 that every tutorial uses, which is another reason not to type this by hand. `netrec config` prints it if you want to see it.

Once the browser is up: log in, and go to the page you care about, exactly as you normally would. Then check netrec can see it:

```
netrec tabs
```

If that lists your tabs, you are connected and you need not think about the port again for this browser session. If it is empty, the browser was started some other way — close it and use the shortcut.

If a browser happens to be running with debugging on some other port already, netrec can find and adopt it instead:

```
netrec config --detect          probes localhost and reports what it finds
netrec config --detect --adopt  and saves that port as the setting
```

## Step 3 — capture something

The order matters here, and it is the one thing people get wrong. Attach FIRST, then do the thing in the browser. netrec records from the moment it attaches; it cannot go back in time for a request that already happened.

```
netrec rec --tab invoices -s myjob
```

`--tab invoices` matches a substring of the tab title or URL. `-s myjob` names the capture so you can have several and tell them apart. The command returns immediately — recording carries on in a background daemon and outlives the command, so you are never tied to a window you have to keep open. It prints exactly what it attached to, and it exits non-zero if attachment was not confirmed, which means you can trust it: if it said it attached, it attached.

Now stamp the timeline so you can find your click again in a moment:

```
netrec mark "about to press Save"
```

Go to the browser and press Save.

## Step 4 — read back what the browser sent

```
netrec ls --writes --since-mark
```

That is the money command: every POST, PUT, PATCH and DELETE since that mark. Usually it is a handful of lines and the one you want is obvious. Then look at it properly:

```
netrec show <id> --req            the request: URL, headers, body
netrec show <id>                  both sides
netrec curl <id>                  a ready-to-run curl command, secrets masked
netrec curl <id> --reveal         the same with the real cookie in it, so it actually runs
```

Other useful selections:

```
netrec ls --api --since 5m        XHR and fetch only, last five minutes; drops image and font noise
netrec ls --status 4xx            what failed
netrec ls --host api.example.com
netrec ls -m POST -T XHR
```

And if you only want one field out of a large JSON response, ask for it rather than printing 200 KB and piping it somewhere:

```
netrec show <id> --jq "{count: .resp.count, first: .resp.results[0].name}"
```

When you are finished:

```
netrec rec stop -s myjob
netrec stop                       shut the daemon down entirely
```

## Cookies, without waiting for traffic

A different question, and often the more useful one: not what did the browser send, but what is the browser holding right now.

```
netrec cookies --url portal.example.com
netrec cookies --url portal.example.com --reveal
```

This reads the browser cookie jar directly, so it needs no traffic at all — you do not have to reload a page to make a session cookie appear. It also prints the expiry, which is usually the answer to why a script suddenly started getting 401s.

## What it does not capture — read this once

Trust nothing netrec did not say it saw. When you attach, it prints what it attached to; that is the honest boundary.

It captures the page and its children — iframes, workers and service workers are picked up automatically as they appear, so requests routed through a service worker are recorded. It follows tabs that navigate into your pattern later.

It will miss the opening burst of a brand-new tab, if the tab is created and starts firing requests before adoption wins the race: its first document, and sometimes the first request after it. So attach to the tab that is already open, then act. Everything from the moment of attachment is complete.

Records land when the response finishes, so a request still in flight shows as pending. Only the final hop of a redirect chain is kept. Response bodies are capped at 2 MB per side and truncation is flagged in the record rather than hidden.

## The captures contain live credentials

This matters. The store on disk holds real session cookies and real auth tokens for whatever you were logged into. Output masks Cookie, Authorization and token headers by default, and that masking happens inside the daemon, so the secrets do not even reach your screen unless you deliberately pass `--reveal`.

Getting them off the disk again is `clear`, and it is a dry run unless you add `--yes`:

```
netrec clear -s myjob                  shows what would go; touches nothing
netrec clear -s myjob --yes --marks
netrec clear --older-than 7d --yes     routine housekeeping
netrec clear --all --yes               drop the database entirely and start empty
```

Only `--all` genuinely removes the bytes from disk — a selective delete removes the rows, but the data can sit in the storage file until compaction, and `clear` tells you so rather than pretending otherwise. Clear a capture when you are done with it, the same way you would shred a printout of a password.

The store lives in `%USERPROFILE%\cdpai\netrec\`. Do not put it in a shared or synced folder.

## Working with an AI coding agent

The intended workflow is a conversation. You tell the agent what you are about to do, you do it, and the agent reads the traffic:

- you say: I am going to click Save on this invoice, record it
- the agent runs `netrec rec --tab invoices -s inv` then `netrec mark "before save"`
- you click Save, and say so
- the agent runs `netrec ls --writes --since-mark`, then `netrec show <id> --req`
- the agent now writes you working code, because it has the real URL, the real headers and the real payload rather than a guess

The two things worth telling the agent explicitly: attach before you click, and ask for `--reveal` only at the moment it actually needs to run a request, because a revealed cookie in a chat transcript is a leaked cookie.

The same loop is how you get a crawler or a scraper built for a site with no documented API. One capture gives the agent a single request; what it actually needs is the *variation*, so do each variant behind its own mark — page two, a filter applied, a different item, the last page. From a handful of those the agent can see which parameter carries the pagination, which header the server actually insists on, and which field tells it when to stop, and it can then write a client that works instead of one that guesses.

## If something is not working

`netrec status` — is the daemon up, what is it recording, how much is stored. It never starts the daemon, so it is always a safe thing to run.

`netrec tabs` returns nothing or errors — the browser was not started with the debugging port. Close it completely and use the "Start browser for netrec" shortcut. A browser that was already running when the flag was added does not pick it up, and says nothing about it.

`netrec rec` exits non-zero — no tab matched. Run `netrec tabs` and match a substring you can actually see, or use `--wait 30` to sit and wait for the tab to appear.

Nothing captured at all — you almost certainly acted before attaching. Attach, mark, then act.

netrec is not a recognised command — you have not opened a new terminal since running `install.cmd`, or PATH was not updated. Run `netrec.exe` from inside the install folder to confirm the tool itself is fine.

The daemon log is at `%USERPROFILE%\cdpai\netrec\daemon.log`, and setting `NETREC_DEBUG=1` makes it verbose about what the browser is telling it.

## Where the full reference lives

`README.md` in this folder is the complete document: every verb, every flag, the control API on `127.0.0.1:2477` for chaining without starting a JVM, and the ArcadeDB endpoint if you want to run raw SQL against the capture.
