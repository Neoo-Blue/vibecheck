# Vibecheck

**English** · [中文](README.zh-CN.md)

**Vibe check before you reply.** An Android accessibility overlay that reads the messages
visible in a chat, asks TypeSafe's System One model **Jev** what is really going on, and sits
as a small bubble at the edge of the screen. Tap the bubble for the card. Everything runs on
the phone: no computer, no root, no adb.

Supported apps (tick them in settings): Messenger, Google Messages (SMS / RCS), WhatsApp,
Telegram, Instagram, Signal, Samsung Messages, Snapchat, Viber, X, Teams, WeChat, QQ, Soul,
LINE, KakaoTalk, plus any chat app whose layout puts them on the left and you on the right
(enter the package name). Discord and Slack put every message on the left, so the geometry
cannot tell you apart; they are left out.

The in-app UI is bilingual (English and Chinese); it follows the phone's language and can be switched at the top of the app.

## Judging happens in two steps

Every new message from the other side gets a quick **triage**:

| id | type | question |
|---|---|---|
| `situation` | choice | what kind of chat: romance / flirting / friend / family / colleague / client / stranger / customer service |
| `intent` | choice | what they actually mean |
| `danger` | score | how badly this turn can go, x / 6 |
| `urgency` | noul | does this need an answer now |

Small talk, banter and plain information stop there: a dot on the bubble, one line on the
card. Only a turn worth a closer look gets the second step, and **what gets asked depends on
the situation**, so a work chat and a first chat with a stranger show different headers:

| situation | second step |
|---|---|
| colleague / client / customer service | what they are asking for, time pressure, does going along commit me to something, best move |
| stranger | their interest, should I push, where to take the topic, best move |
| friend / family | their mood, what they need right now, best move |
| romance / flirting | are they being literal, what they need right now, best move |

Jev only returns judgments with probabilities; it never writes lines for you. The "suggested
move" at the bottom of the card is computed in code from `danger`.

Jev can be reached two ways, same request and same answers: TypeSafe's own API
(`api.typesafe.ai`, the default) or OpenRouter's decisions endpoint
(`openrouter.ai/api/alpha/decisions`, model `~typesafe/jev-latest`). The question sets are
written in Chinese and stay that way in both UI languages, because their option keys are what
the per-person learning is stored under; Jev reads English chats just as well.

## Install

1. Build it yourself (see Development) and copy `app/build/outputs/apk/debug/app-debug.apk`
   to the phone, grab the APK from the latest release, or download the `vibecheck-debug-apk`
   artifact of the latest successful "Build" run on GitHub Actions.
2. Open the app. On the **Setup** tab, paste a TypeSafe API key and tap **Test** to confirm it
   works. For deep analysis, reply drafts and "learn this person", add an OpenRouter key.
   Everything saves by itself.
   **No TypeSafe key?** Jev is also served by OpenRouter: pick "Jev via OpenRouter" under
   Judging engine and one OpenRouter key covers everything. TypeSafe's own API is the default.
3. The **Get started** checklist at the top says what is still missing. Tap **Open accessibility
   settings** and enable **Vibecheck**.
   **Toggle greyed out?** Android 13+ restricts sideloaded apps. Tap **App info** in the checklist →
   menu (⋮) → Allow restricted settings → confirm your lock screen, then enable it.
4. Open a chat. A small bubble appears at the right edge; about half a second after a
   message arrives it takes on a colour. Tap it for the card.

**Updating from 6.5 or earlier:** if "Learn" does not scroll after the update, switch the
accessibility service off and on once, so Android picks up its new gesture permission.

## The app: Setup and Tools

The app has two tabs, and everything saves as you go:

- **Setup** holds only what it takes to get going: the checklist, the judging engine and keys,
  which apps to watch, a few behaviour switches (judging on, automatic deep reads, OCR, learning,
  remembering people) and the general relationship context.
- **Tools** holds everything else, one simple tile each: **Pause** (1 hour, or until 8 am),
  **People** (see and edit what was learned per person, pause a person, merge, forget, clean up
  near-empty records), **Card** (text size, which buttons the card shows, buzz on high risk,
  bubble position), **Usage** (paid calls today and in total), **Backup** (export / import people
  memory as a file; keys are not included), **Models**, **Keep it running** (battery optimisation
  and app info shortcuts), **Diagnostics** (live status, debug mode, the LAN endpoint) and
  **How it works**.

Every tile has a **Hide** button. Hidden tiles collect at the bottom of the Tools tab, where one
tap brings a tile back (or **Show all**), so the dashboard only shows what you use.

No "draw over other apps" permission is needed: the card is a `TYPE_ACCESSIBILITY_OVERLAY`,
which an accessibility service owns outright. It is a trusted window, so `setHideOverlayWindows()`
cannot hide it and Android 12+ does not drop its touches as untrusted.

## Learning: a one-step contextual bandit

Jev is a hosted model with no gradient to push back, so learning lives on the app side and
uses only what the phone can actually observe:

- **context** = the `intent` judged this turn
- **action** = the `action` shown first on the card
- **reward** = how much the next turn's danger dropped (calmer is positive, escalation negative)

Two products:

1. `dangerBias`: a per-person calibration of the danger level. If the model systematically
   over- or under-reads someone, the number on the card drifts to match.
2. The mean reward of each `intent|action` pair. After three samples it starts re-ranking
   "best move"; when experience overrides the model's first choice the card says so.

A sample is only recorded along the full chain "advice shown → you actually replied → they
replied again". Two messages in a row from them say nothing about the advice.

**This is correlation, not causation.** The app sees what happened after the advice appeared,
not whether you followed it. The bandit is stored in SharedPreferences and can be cleared.

### Three levels, with backoff

Each settlement updates three keys and lookup falls back from the most specific:

```
romance|expressing displeasure|apologize first   ← used once it has 3+ samples
*|expressing displeasure|apologize first         ← new situation: fall back to "this intent"
*|*|apologize first                              ← new intent: "how does this action do in general"
```

New situations do not start from zero; the price is coarser advice at first. The same action
can score opposite ways in different situations, and nothing is shared between people.

## The card

**At rest there is only the bubble.** Judging still runs on every new message; the result shows
on the bubble as the danger number and a colour (green → yellow → red). Tap to open, ✕ to
collapse. **Drag** the bubble to move it (it snaps to the nearest edge and remembers the spot);
**long-press** it for the menu. The card is a trusted window, so tapping it does not block the
chat underneath, and taps outside it go straight through. It follows the phone's dark mode and
stays above the keyboard.

The header names who the card is about and the kind of chat, so a misread contact is obvious.
Score answers carry their meaning ("3 / 4 · today", "5 / 6 · risky"), not just a number.

| button | what it does |
|---|---|
| More / Less | expand shows "what kind of chat" and "answer now?" and lays out the deep analysis in full |
| Think | one deep pass through DeepSeek on OpenRouter: what they care about, the trap in this step, a concrete next move |
| Reply | three reply drafts in **your own way of speaking**; tap one to put it into the (empty) reply box, long-press to copy. Nothing is ever sent for you |
| Learn | read this person's whole history and write a profile (see "Learn this person") |
| ⋯ | re-check this screen, pause 1 hour, pause this chat, settings |
| ✕ | back to the bubble |

Think, Reply, Learn and ⋯ can each be switched off under Tools → Card. The deep read and the
reply drafts are separate panels, so an automatic deep read landing later does not wipe the
drafts you were choosing from. Long-press any line on the card to copy it.

Think and Reply only run when you press them (roughly $0.0002 to $0.001 each). Every new message
runs Jev alone: a few hundred milliseconds, cheap. Turns that deserve it also get an automatic
deep pass while you are still in that chat; the bubble shows ✦ when there is something to read.

Each chat keeps its own card: switch to another chat and back, and the last judgment is still
there without asking again. A failed call (no connection, a rejected key) is shown on the card and
retried with backoff; a rejected key is not retried until you change the key.

**Pausing.** ⋯ → Pause 1 hour (or Tools → Pause) stops judging everywhere and resumes by itself.
⋯ → Pause this chat stops judging *and* recording for one person until you resume it from the
card or from Tools → People. The bubble shows ⏸ while paused.

**On WeChat the deep pass also takes a screenshot** and uses a vision model (default
`deepseek/deepseek-v4-flash-vision-exp`), because OCR cannot read stickers and emoji and those
carry most of the emotion in Chinese chat. Apps that expose their view tree give complete text,
so the deep pass there is text-only (default `deepseek/deepseek-v4-pro`). Both model names are
editable in settings.

## It keeps learning even with the card off

Judging costs money; watching does not. Whenever you are in a watched chat, whether the card
is collapsed, dismissed, or judging is switched off entirely, the app still reads the screen,
recognises who you are talking to and records new messages into that person's memory. It just
does not call the model or draw a card.

What it accumulates (all local; raw text is never uploaded for this):

| learned | how |
|---|---|
| how I talk to this person | only my own messages: average length, emoji rate, question rate, apology rate |
| who opens | a silence over 2 hours starts a new session; who sent its first message |
| volume ratio | message counts and average length per side |
| how long I take to reply | gap from "their message appeared" to "my message appeared", observed cases only |
| share of high-risk turns | among turns actually judged |
| days seen | distinct dates |

These enter every judgment as "long-term observations of this relationship" and the deep
prompt too. Each message is counted once: every screen is lined up against the newest messages
already counted for that person (tolerating the odd OCR misread), so sitting on the same screen,
or scrolling up through history and back down, does not inflate the numbers. Timing (who opened,
how long I took) is only taken from messages that arrived while the chat was being watched.
Switch this off under Setup → "Remember people while you chat"; a paused chat is never recorded.

## Per-person memory (all on the phone)

The app reads the contact's name from the chat title bar (falling back to the avatar's
content description on WeChat, or to a fingerprint of the title bar when the name is pure emoji)
and stores everything under "app package + name":

| stored | from | used as |
|---|---|---|
| personal note | typed by you in settings | overrides the generic "relationship context" |
| how I talk to them | statistics over my own messages | `my usual way of speaking` in the state |
| last 8 turns | one `intent / danger / action` line per judgment | `where the last few turns went` in the state |
| this person's bandit | see Learning | calibrates danger, re-ranks the best move |
| profile | 4 to 6 lines written by DeepSeek after "learn this person" | `relationship context` when no note is set |

The same sentence means different things from different people, so nothing is shared between
two people: "apologize first" working on A does not touch B's ranking. Tools → People shows what
has been learned about each person (with search), lets you edit their note, pause them, merge
them with the same person on another app, or forget them. Tools → Backup exports all of it to a
file for a new phone.

Statistics stay local, but their summary (e.g. "avg 12 chars, apologises 8%") goes with each
judgment request. Nothing is uploaded beyond the dozen messages currently on screen, and no
transcript is kept.

### Learn this person

Open the card in a chat and tap Learn. The card drops to the bubble, which shows the running
count, and the app pages up through the history by itself. Each page's new lines are joined
onto the front, aligned by the overlap between pages rather than by de-duplicating text, so
"ok" sent thirty times stays thirty messages. It stops after four pages without new content or
at 60 pages; tap the bubble to stop early and keep what was read. The full read then replaces
message counts and speaking style, and DeepSeek writes the profile. Switching app or page, or an
interrupted service, cancels the read; a read that covers fewer messages than the live count does
not overwrite the statistics.

### One person across apps

When Li on WeChat and Sam on Messenger are the same person, open Tools → People → Li → More… →
"Merge with another person", and choose the other. Counts, speaking style, recent turns, profile and bandit
are folded together (arms pool their samples, the danger bias is weighted by how much each side
learned), and from then on either chat opens the same memory.

## Debugging while it runs

Enable "LAN debug endpoint" under Tools → Diagnostics and any computer on the same Wi-Fi can read
the live state, no adb and no restart:

```
curl "http://<phone-ip>:8848/status?t=<token>"
```

| route | content |
|---|---|
| `/status` | service connected, foreground app, last scan result, last error, pending learning round |
| `/log` | last 200 log lines: events, request timings, learning updates |
| `/tree` | the full node tree of the current screen: class / id / text / desc / long-clickable / bounds |
| `/last` | the last request body sent to TypeSafe and the raw response |
| `/learn` | learned bias and mean reward per action |
| `/learn/reset` | clear the learning state |
| `/rescan` | force a fresh judgment of the current message |
| `/windows` · `/shot` | current window list · whether screenshots work (for the OCR path) |

The token is generated in the app, every route needs it, and "New token" locks the old URL out
immediately. Only callers on the local network (private, link-local or unique-local addresses) are
answered, even if the phone has a public IPv6 address on mobile data. The endpoint is off by
default. **`/tree` and `/last` return chat content verbatim**; switch it off when you are done.

`/tree` is how you answer "is WeChat scrambling the node tree on this version": compare it
with what is actually on screen.

## Known limits

- **WeChat 8.0.52+ scrambles node content for third-party accessibility services** (ids and
  text change on every snapshot). The app falls back to OCR of the screen; emoji and stickers
  are lost on that path, which is why the deep pass attaches a screenshot.
- **Telegram draws its bubbles on a canvas**: there is no text in the node tree, so it reads
  through OCR like WeChat.
- OCR uses Google Play services' on-device text recognition, which downloads its model on first
  use. Phones without Play services (many phones sold in China) cannot run the OCR path; the error
  shows under Tools → Diagnostics. On Android 14+ the capture is of the chat's own window, so an
  open card or the keyboard never hides messages from OCR.
- Soul has no documented view structure; classification is the same geometry rule (hugging
  the left = them, hugging the right = me) and may need adjusting on a new version. The same
  goes for the other apps; the status labels they print inside the list (Seen, Delivered,
  SMS · Now, Active now, call stubs, reactions) are filtered by known patterns and anything
  new will leak into the context.
- No view ids are used anywhere (WeChat renames them every release): only TextView + text +
  screen position + long-clickability.
- Only **messages visible on screen**, at most the last 12; no database, no history, except
  during an explicit "learn this person" read.
- Chinese ROMs (MIUI / ColorOS / HarmonyOS) kill background accessibility services; exclude the
  app from battery optimisation (Tools → Keep it running).

## Privacy

Chat text goes to `api.typesafe.ai` for judging (or to `openrouter.ai` if you chose that
engine) and, when you press the buttons, to `openrouter.ai`. API keys and learning state live in the app's private SharedPreferences.
The debug endpoint is LAN-only and token-gated but can read chat content: do not enable it on
public Wi-Fi. This is a personal tool for your own phone and your own conversations.

## Development

Needs JDK 17 and an Android SDK with API 35. The Gradle wrapper pins Gradle 8.9 (checksum
verified):

```bash
ANDROID_HOME=~/android-sdk ./gradlew --no-daemon testDebugUnitTest assembleDebug
```

GitHub Actions runs the same on every push and pull request and uploads the debug APK.

`Chat.kt`, `Jev.kt`, `Person.kt`, `Relation.kt` and `Learner.kt` do not depend on Android:
bubble detection, system-row filtering, the two-step question sets, card rendering, bandit
updates and merging, page alignment for the history read, and the alignment that keeps passive
counting from double counting are covered by the unit tests under `app/src/test/`.
