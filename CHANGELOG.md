# Changelog

APKs for every version are on the [releases page](https://github.com/Neoo-Blue/vibecheck/releases).
Each section below is also that release's notes: the Release workflow publishes the section whose
heading matches the tag.

## 6.7.0

**Learn this person now learns everything.** It reads your whole history with them (no more
60-page limit; bigger scrolls and shorter waits, about a page a second), keeps it on the phone, and tops it up with new messages
as you chat; learning again only reads what is new. The profile is written from all of it, in
batches, and is far more detailed: what they are to you and how close you are, who they are, how
you get along, how they talk and how you talk to them (with quotes), likes and dislikes, what you
talk about, running jokes, things that happened, sore spots, and what helps when they are down.
The card shows how far it has got.

**Replies that sound like you.** Reply drafts now get the whole profile plus up to eight real
exchanges from your history (the ones most like what they just said, and the latest) and the
things you say most often, and are told to write like you rather than like a template. Deep
reads use the profile too, and the card adds one line from it when it fits the moment.

**What they are to you, and how close, are two separate things.** A friend whose profile said
"best friend" was judged 恋爱或亲密关系 91%: being close is not being a couple. Each person now has
a relationship (partner, flirting, friend, family, colleague, client, stranger, customer service)
and a closeness (distant, casual, familiar, close, very close). Learning fills both in; you can set
them on the card (⋯ → Relationship) or under Tools → People. Once the relationship is known, Jev is
told instead of asked, so one message can no longer turn a friend into a partner. Profiles learned
before this version are read for both from their first line, so there is no need to learn again.
- The option 恋爱或亲密关系 is now 恋人或伴侣, friends explicitly include best friends, and the
  question asks about the two of you, not the topic. What was learned under the old name carries over.
- With no note, profile or general context, every request used to say "a chat within an intimate
  relationship". Now nothing is assumed.

**Emoji names.** Names like ❤️, 🧑🏻‍💻 or ✨小鱼✨ are recognised where the app gives text. Where it
has to use OCR (WeChat, Telegram), emoji cannot be read at all, so:
- such a chat is known by the avatar beside their messages, and the title bar is remembered
  against it for screens that show only your own messages;
- it is labelled with the emoji itself, a small picture of the name from the chat's title bar,
  on the card and in People, never with an internal code;
- the real name, emoji and all, comes from their message notifications, which also puts the
  emoji back on names OCR read only in part ("欧欧" → "欧欧🌸");
- ⋯ → Rename names anyone by hand, emoji included;
- "对方正在输入…" is no longer taken for a name.

Also:
- After learning, the card is judged again, on the same messages, with what was learned, and the
  profile stays under it. Changing a relationship, closeness, name or note does the same.
- A history read leaves the chat scrolled far up; those old screens used to be judged as if they
  were the conversation. Judging now waits until the newest messages are back on screen.
- A note no longer hides the learned profile: both are sent.
- Judging the same message again (Re-check included) no longer counts it twice in that person's
  statistics and history.
- The setting called "relationship context" is now "general context", with a warning not to
  describe one person there: it applies to everyone without their own.

**Privacy:** learning a person sends your whole history with them to your OpenRouter model to
write the profile. Notifications from watched apps are now read for the sender's name only
(kept in memory, never stored or sent). Kept histories stay in app-private storage and are not in Backup exports;
Tools → People → More… deletes one.

中文：「学习此人」现在会翻完你们的全部聊天记录（不再限 60 页，翻得更快，大约一秒一页），存在手机上，之后边聊边补；再学习只读新增的部分。档案按全部记录分批写成，详细得多：Ta 是你的什么人、有多亲近、Ta 是谁、相处方式、Ta 和你各自怎么说话（附原话）、喜好、常聊的事、梗、重要的事、雷区、Ta 难过时怎么办。回复草稿会带上完整档案和最多 8 段你以前对 Ta 的真实回复，写得像你本人而不是模板。关系和亲近程度分开：关系好不等于是恋人；知道关系后直接告诉 Jev，不再让它猜，不会再把好朋友判成恋爱。「恋爱或亲密关系」改名「恋人或伴侣」，没有背景时不再默认是亲密关系。名字是 emoji 的联系人：能读到文字的应用直接认；截图识字的（微信、Telegram）按头像认，并用标题栏上那个 emoji 的小图来标，不再显示代码；Ta 发来消息后，从通知里自动拿到带 emoji 的真名（「欧欧」也会补成「欧欧🌸」）；⋯ → 改名可以手动起名；「对方正在输入…」不再被当成名字。学习后停在旧记录页时不再拿旧消息去判断。注意：学习会把和这个人的全部聊天记录发给你的 OpenRouter 模型。

## 6.6.0

A pass over the whole app: fixes across the service, the card and learning, a new settings layout,
and a set of quality-of-life features.

**New layout.** Two tabs. *Setup* holds only what it takes to get going: a checklist of what is
still missing, the engine and keys, which apps to watch, and a few switches. *Tools* holds
everything else as simple tiles (Pause, People, Card, Usage, Backup, Models, Keep it running,
Diagnostics, How it works). Any tile can be hidden with one tap and brought back from the bottom of
the tab. Everything saves by itself.

**Card and bubble.** Drag the bubble anywhere (it snaps to an edge and remembers the spot);
long-press it for re-check, pause 1 hour, pause this chat and settings. The card says who it is
about, follows dark mode, has a text size and stays above the keyboard. Tapping a reply draft puts
it in the empty reply box (it is never sent for you); long-press copies any line. The deep read and
the drafts are separate panels. Think, Reply, Learn and ⋯ can each be switched off.

**Fixes.**
- "Learn this person" now actually scrolls: a missing gesture permission made Android drop every
  scroll, so reads stopped after the first screen.
- Switching chats no longer shows the previous person's card, or writes replies about them.
- A slow deep read no longer holds up judging or screen reading.
- Failed calls back off instead of retrying on every screen change; a rejected key waits 10 minutes
  or until you fix it.
- WeChat and Telegram messages that arrive right after a screen read are now judged.
- The keyboard and notification pop-ups no longer close the card.
- Scrolling through history no longer inflates the per-person statistics, and messages like
  "video call tonight?" are no longer dropped as system notices.
- The LAN debug endpoint really is LAN-only now, and a new token locks the old URL out at once.

**Upgrading from 6.5.1 or earlier:** after installing, switch the accessibility service off and on
once so Android grants the new gesture permission.

中文：全面修了一遍服务、悬浮卡片和学习逻辑。新布局分两页：「设置」只放上手必需的，「工具」把其他功能做成一块块卡片，每块都能一键隐藏、在最下面一键恢复，改动自动保存。气泡可以拖动，长按出菜单；点回复草稿直接填进空的输入框（不会发送）。「学习此人」终于能自动翻页；切换聊天不再显示上一个人的判断；深思不再卡住判断；调用失败会逐步延长重试间隔；翻聊天记录不再把统计刷高。从 6.5.1 或更早升级后，把无障碍服务关掉再打开一次。

## 6.5.1

Same as 6.5, plus: the app name and the default UI language follow the phone's locale (English
phones see "Vibecheck", Chinese phones see "Vibecheck 读空气"), the deep-analysis-ready badge is
translated, and the English README is fully English.

中文：应用名和默认界面语言跟随手机语言，英文 README 已全英文。

## 6.5

**Jev via OpenRouter.** Under *Judging engine* pick "Jev via OpenRouter" and one OpenRouter key
covers judging, deep analysis, replies and learn-this-person (OpenRouter serves TypeSafe's Jev at
`/api/alpha/decisions`, model `~typesafe/jev-latest`). TypeSafe's own API remains the default.

**English / 中文 switch** at the top of the settings screen: settings, card, prompts and summaries
follow it. The question sets stay Chinese internally so what has been learned about each person
survives a switch; Jev reads English chats either way.

中文：判断引擎可选「Jev，走 OpenRouter」，一个 OpenRouter Key 全搞定；设置最上面可切换中文 / English。

## 6.4

First public release: an Android accessibility overlay that reads the chat on screen, asks
TypeSafe's Jev what is really going on, and sits as a small bubble; tap it for the card. Two-step
judging, learn this person, one person across apps, per-person memory, a learned profile and a
contextual bandit, all on the phone.

中文：首个公开版本。读取聊天页可见消息，交给 TypeSafe Jev 判断，用屏幕边缘的小气泡告诉你；人物记忆、档案和 bandit 全在手机上。
