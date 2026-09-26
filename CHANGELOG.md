# Changelog

APKs for every version are on the [releases page](https://github.com/Neoo-Blue/vibecheck/releases).
Each section below is also that release's notes: the Release workflow publishes the section whose
heading matches the tag.

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
