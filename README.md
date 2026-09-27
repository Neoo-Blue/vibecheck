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
| `situation` | choice | who they are to you: partner / flirting / friend / family / colleague / client / stranger / customer service. Asked only while that is unknown (see "Who they are to you") |
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
| partner / flirting | are they being literal, what they need right now, best move |

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
  remembering people) and a general context for people you have not described.
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
partner|expressing displeasure|apologize first   ← used once it has 3+ samples
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
| Learn | read and keep your whole history with this person and write a detailed profile (see "Learn this person") |
| ⋯ | re-check this screen, who they are to you and how close, rename, pause 1 hour, pause this chat, settings |
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
content description on WeChat) and stores everything under "app package + name". Emoji in names
are fine where the app gives text. OCR (WeChat, Telegram) cannot see emoji at all:
- A chat whose name is only emoji is known by the avatar beside their messages, and the title
  bar is remembered against it for screens where only your own messages show.
- Such a person is shown with the emoji itself: a small picture of the name cut from the chat's
  title bar, on the card and in Tools → People, never an internal code.
- Their real name, emoji and all, is picked up from their message notifications: once a
  notification's message is on screen in that chat, its sender's name becomes theirs. The same
  puts the emoji back on names OCR read only in part ("欧欧" becomes "欧欧🌸").
- ⋯ → Rename on the card names anyone by hand (type or paste emoji); your name always wins.
- A "typing…" indicator in place of the name is not taken for one.
- Labels apps put on anyone ("Souler" on every Soul avatar, 对方, 用户) are never a name; a name made
  only of marks ("...", "。") counts where the app gives text. Debug mode lists the title bar's
  texts and avatar labels, which shows why a name was or was not read.

| stored | from | used as |
|---|---|---|
| who they are to you | learned from the history, or chosen on the card (⋯ → Relationship) or under Tools → People | `我和对方的关系` in the state; the situation question is then not asked |
| how close you are | the same, as its own choice: distant, casual, familiar, close, very close | `我们有多亲近` in the state, and on the card title |
| a name you gave them | ⋯ → Rename, or Tools → People | shown everywhere, and the name the models are given |
| personal note | typed by you in settings | `relationship context`, together with the profile; both replace the general context |
| how I talk to them | statistics over my own messages | `my usual way of speaking` in the state |
| last 8 turns | one `intent / danger / action` line per judgment | `where the last few turns went` in the state |
| this person's bandit | see Learning | calibrates danger, re-ranks the best move |
| kept history | the whole chat, from "learn this person", topped up live with new messages | what the profile is written from; reply drafts pick real past exchanges from it |
| profile | sections written by DeepSeek from the whole kept history | the full text for deep reads and drafts; a short brief as `relationship context`; one matching line on the card |

The same sentence means different things from different people, so nothing is shared between
two people: "apologize first" working on A does not touch B's ranking. Tools → People shows what
has been learned about each person (with search), lets you edit their note, pause them, merge
them with the same person on another app, or forget them. Tools → Backup exports all of it to a
file for a new phone.

Statistics stay local, but their summary (e.g. "avg 12 chars, apologises 8%") goes with each
judgment request. For people you have not learned, nothing is uploaded beyond the dozen messages
currently on screen and no transcript is kept. For people you learn, see below.

### Learn this person

Open the card in a chat and tap Learn. The card drops to the bubble, which shows the running
count, and the app pages up through **the whole history** by itself, about a page a second (a
little slower where it has to use OCR). Each page's new lines are joined onto the front, aligned
by the overlap between pages rather than by de-duplicating text, so "ok" sent thirty times stays
thirty messages. It stops at the first message (five pages in a row with nothing new); tap the
bubble to stop early and keep what was read. Keep the screen on and the chat open while it runs.

What was read is kept on the phone (app-private storage, one file per chat) and topped up with
new messages as you chat. The next Learn only reads back until it meets what is kept, so it
takes seconds. Tools → People → More… deletes a kept history; forgetting a person deletes it too.

Then the profile. A long history is sent to your OpenRouter model in stretches of about 12,000
characters, three at a time: notes on each stretch, then one profile from all the notes. Notes
are cached by the stretch's content, so a later Learn pays only for what is new. The card shows
how far it has got. The profile starts with what they are to you and how close you are, then
sections: who they are, how you get along, how they talk, how you talk to them (both with
quotes), likes, dislikes, what you talk about, running jokes, things that happened, sore spots,
and what helps when they are down. The message counts and speaking style are recomputed from
the whole history, and the card on screen is judged again, on the same messages, with what was
learned.

Where it goes: the judge gets a short brief (how you get along, how they talk, sore spots, what
helps); deep reads and reply drafts get the whole profile; reply drafts also get up to eight
real exchanges from the history (the ones most like what they just said, and the latest) and
the things you say most often, so they sound like you and not like a template. The card adds
one line from the profile when it fits: what helps when they are down, their sore spots when it
could go wrong, your running jokes when they are joking.

The read leaves the chat scrolled far up; those old screens are not judged, and judging picks up
again once the newest messages are back on screen. Switching app or page, or an interrupted
service, cancels the read.

### Who they are to you

Two separate things: what they are to you, and how close you are. Being close is not being a
couple, and the old option "恋爱或亲密关系" treated it as if it were.

Each person can carry a relationship: partner, flirting, friend, family, colleague, client,
stranger or customer service, the same options as the `situation` question, and a closeness:
distant, casual, familiar, close or very close. Once the relationship is known,
Jev is told instead of asked, so every message is judged as what it is and the card can no
longer call a best friend a partner because the two of you were talking about their boyfriend.
Without one, the question asks about the two of you rather than the topic, and nothing is
assumed.

Both are filled in by "learn this person" (the profile's first two lines), and can be chosen by
hand on the card (⋯ → Relationship) or under Tools → People; your choice wins over a later read,
and "Work it out" / "Not sure" hands it back to the reads. Profiles written before 6.7.0 are read
for both from their first line when that line is unambiguous ("好友或死党，关系亲密" is a friend,
close). When the relationship, the closeness, a name, a profile or a note changes, the card for
that chat is judged again, and the result of the read stays under the new card.

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
- Judging sees only **messages visible on screen**, at most the last 12. A whole history is
  read and kept only for people you learn.
- Learning reads what the chat shows as text: pictures, stickers, voice messages and files are
  not in it, and a long run of them can look like the top of the history and end the read early
  (tap Learn again; it continues). WeChat's own search and dates are not used, so the kept history
  has no timestamps.
- Chinese ROMs (MIUI / ColorOS / HarmonyOS) kill background accessibility services; exclude the
  app from battery optimisation (Tools → Keep it running).

## Privacy

Notifications from the watched apps are read only for the sender's name and message, kept in
memory (the last 40) to name emoji-only contacts, and never stored or sent anywhere.

Chat text goes to `api.typesafe.ai` for judging (or to `openrouter.ai` if you chose that
engine) and, when you press the buttons, to `openrouter.ai`. **Learning a person sends their
whole history to your OpenRouter model** to write the profile, and reply drafts send a few past
exchanges with them. API keys and learning state live in the app's private SharedPreferences;
kept histories are files in app-private storage, not included in Backup exports.
The debug endpoint is LAN-only and token-gated but can read chat content: do not enable it on
public Wi-Fi. This is a personal tool for your own phone and your own conversations.

## Development

Needs JDK 17 and an Android SDK with API 35. The Gradle wrapper pins Gradle 8.9 (checksum
verified):

```bash
ANDROID_HOME=~/android-sdk ./gradlew --no-daemon testDebugUnitTest assembleDebug
```

GitHub Actions runs the same on every push and pull request and uploads the debug APK.

**Releasing.** Bump `versionCode` / `versionName` in `app/build.gradle.kts`, add a section for the
version to `CHANGELOG.md`, and merge to `master`: the Release workflow sees a version with no release
yet, runs the tests, builds `vibecheck-<version>.apk` and publishes it with that section as the
notes. Pushing the tag `v<versionName>` or running the workflow by hand works too (and replaces the
APK of an existing release).

**Signing.** Releases from 6.7.1 on are all signed with one key, `app/signing/release.p12`, so each
installs over the last as an update. The file is encrypted (AES-256 under a long random password) and
useless without the password, which is the repository secret `RELEASE_KEY_PASSWORD`. Without that
secret the Release workflow publishes nothing, rather than an APK that could not update anything;
and it checks the APK's certificate (SHA-256 `d364f1fb…29f1b017`) before publishing. To build a
release-signed APK locally, set `RELEASE_KEY_PASSWORD` in the environment; without it `assembleRelease`
signs with the debug key, which will not install over a release.

`Chat.kt`, `Jev.kt`, `Person.kt`, `Relation.kt` and `Learner.kt` do not depend on Android:
bubble detection, system-row filtering, the two-step question sets, card rendering, bandit
updates and merging, page alignment for the history read, and the alignment that keeps passive
counting from double counting are covered by the unit tests under `app/src/test/`.
