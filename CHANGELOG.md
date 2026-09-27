# Changelog

APKs for every version are on the [releases page](https://github.com/Neoo-Blue/vibecheck/releases).
Each section below is also that release's notes: the Release workflow publishes the section whose
heading matches the tag.

## 6.8.0

**It learns about you too, across every chat.** Setup → Behaviour → Learn about me, on by default.
- **A day log.** What is said as it happens in any watched chat is logged by day on the phone,
  with the time and whose chat it was.
- **Day write-ups.** Each finished day is written up in a few lines: what you did, where you went,
  who you talked with, plans made, how you felt. Today can be written up on demand.
- **A profile of you.** Whenever someone is learned, their history is also read for what it shows
  about you. That is merged with your recent days and the people in your life into one profile:
  who you are, how you talk and how that differs between people, likes and dislikes, what you
  have been busy with, what you care about, and the people around you.
- **Me, on the People tab.** It sits at the top, and its page has:
  - the profile, section by section;
  - every day written up;
  - how you write across chats;
  - buttons to write it again or delete it.
- **In replies.** Reply drafts and deep reads now draw on how you talk, who you are, what you have
  been busy with, your last three days and what you said in other chats today. They are told to
  use only that and invent nothing.

The log stays on the phone for 90 days; each day is sent to the Think model to be written up.

中文：
- **跨所有聊天学你自己**（「设置 → 行为 → 了解我」，默认开）：
  - **每天的记录**：每个监听的聊天里实时出现的消息，按天记在手机上，带时间和是哪个聊天；
  - **每天的小结**：过完一天写成几行，你做了什么、去了哪、和谁聊了什么、定了什么计划、心情怎么样，今天的也可以随时点「总结今天」；
  - **关于你的档案**：每学习一个人，也会从你们的聊天里挑出关于你的事，再和最近每天的小结、你身边的人合成一份：你是谁、你怎么说话（对不同的人有什么不同）、喜欢和不喜欢、最近在忙什么、在意什么、身边有哪些人。
- **「人物」页最上面有「我」**，点进去是：
  - 分段的档案；
  - 每天的小结；
  - 你在各个聊天里的说话方式；
  - 重新整理和清空的按钮。
- **回复用上了你**：回复草稿和深思会参考你怎么说话、你是谁、最近在忙什么、最近三天和今天在别的聊天里说过的话。只用写着的，不编。
- **隐私**：记录在手机上存 90 天，每天的记录会发给深思模型写小结。

## 6.7.4

**People get a tab of their own.** Everyone remembered is on the new People tab. Each card shows:
- their name, or the emoji picture of it;
- their apps and what they are to you;
- how many messages were learned;
- one line from their profile.

Tap someone for their page:
- what they are to you and how close you are, changeable there;
- the profile, section by section, with when it was learned;
- what was learned while you chatted: message counts, how you write to them, and which moves
  calmed things down or made them worse, in words;
- their name and context, edited there;
- pause, merge with another app, retake the name picture, delete the kept history, forget.

The People and Models tiles have left Tools.

**Models are chosen in Setup, from options that say what they are good at.** The reply model is
now Kimi K2.6 (`moonshotai/kimi-k2.6`): it has the highest EQ-Bench Creative Writing score among
open-weight models, Chinese is its first language, it reads screenshots, it answers at about 70
tokens a second, and the 18+ switch works with it. The options:
- Reply:
  - Kimi K2.6;
  - Kimi K3: writes best, #2 on EQ-Bench Creative Writing behind Claude Opus 5; slower and dearer;
  - DeepSeek V4.1 Flash: fastest and cheapest;
  - Qwen 3.8 Max: the most natural Chinese, but it filters content, so no adult replies.
- Think and Learn: DeepSeek V4 Pro, Kimi K3 or Qwen 3.8 Max.
- Other…: any OpenRouter model id.

A model that reads no images gets the text alone when a screenshot would have gone along. Earlier
settings pages saved the default model as if you had chosen it, so a new default never reached
you. Those saved defaults are cleared once, and a default is no longer saved at all.

**The wrong picture for an emoji name.** A contact named 🍵 showed a picture of a notification
("…improvements and QoL feat… vibecheck · Default") in place of their name. The name picture is
cut from the chat's title bar, and a notification had slid over it at that moment; the first
picture was kept for good.
- Nothing is cut from the title bar, or fingerprinted there, while a notification or our own card
  covers it.
- A picture is kept only once two looks agree.
- A kept picture is replaced once three looks in a row agree with each other and not with it, so
  a wrong one mends itself.
- The avatar that identifies such a chat is also taken from a message that nothing covers.
- The person's page can retake the picture.

中文：
- **「人物」页**：新标签页，列出记住的每个人。卡片上有：
  - 名字或名字的 emoji 小图；
  - 应用、关系；
  - 学了多少条；
  - 档案里的一句话。
- **每个人的专页**，点进去能看到：
  - 关系和亲近程度（可以直接改）；
  - 分段的档案和学习时间；
  - 边聊边学到的：话量、你对 Ta 的说话方式，哪些做法让气氛缓和、哪些更僵，用大白话写出来；
  - 名字和专属背景，可以在这里改；
  - 暂停、跨应用合并、重新截名字图片、删除存档、忘记。
- **模型在「设置」里选**，每个选项都写着擅长什么：
  - 回复默认换成 Kimi K2.6（开源模型里创意写作第一，中文母语，能看图，每秒约 70 字，成人回复也能用）；
  - 还可以选 Kimi K3（写得最好，慢一些、贵一些）、DeepSeek V4.1 Flash（最快最省）、通义千问 3.8 Max（中文语感最自然，但会过滤内容，不写成人回复）；
  - 深思和学习可以选 DeepSeek V4 Pro、Kimi K3、通义千问 3.8 Max；
  - 「其他模型…」可以填任何 OpenRouter 模型名；
  - 不能看图的模型只发文字。
- **修复：emoji 名字的人显示成一张通知截图**：
  - 截名字图片时正好有通知盖在标题栏上，而第一张图会一直留着；
  - 现在标题栏被通知或卡片盖住时不截图，也不算指纹；
  - 两次看到一样才保存；
  - 已存的图如果连续三次都跟新看到的不一样，就自动换掉；
  - 专页里也可以手动「重新截」。

## 6.7.3

**Faster replies.** Drafts took a long time for four reasons, all fixed:
- the model thought before writing;
- nothing showed until the whole answer was done;
- OpenRouter's default routing leans toward the cheapest provider, and the same DeepSeek model
  runs anywhere from 4 to 57 tokens a second depending on who serves it;
- chats read by OCR used an experimental vision model.

Now:
- Reply drafts use the reply model, default `deepseek/deepseek-v4.1-flash` (DeepSeek's newest,
  released 2026-09-10; fast, cheap, and it reads screenshots itself). They skip thinking; a
  model that cannot skip it thinks a little instead.
- Answers are streamed: the read and each draft show as soon as they are written, and the deep
  read line by line.
- Every call goes to the fastest provider for the model, unless its name says how to route
  (`:nitro`, `:floor`).
- Tools → Models has a Think and Learn model (default `deepseek/deepseek-v4-pro`, thinks a
  little) and a Reply and screenshot model. Each has a Test button that shows how long an
  answer took. If you never changed the old vision model, it moves to the new default.
- Streaming also keeps long profile writes from sitting silent on the connection.

**Adult replies (18+), off by default** (Setup → Behaviour). When on, drafts can be as
suggestive or explicit as the chat already is: when they are talking about sex or clearly
enjoying a flirt, or with a partner you talk like this with. They follow the other person's
lead and go no further than they have shown they want. Nothing of the kind is written after a
no, a hesitation or a change of subject, or with anyone who may be under 18. The deep read
names sexual subtext plainly when it is on.

中文：
- 回复变快了。以前慢有四个原因：
  - 模型先思考再写；
  - 整段写完才显示；
  - OpenRouter 默认偏向便宜的服务商，同一个 DeepSeek 模型不同服务商每秒 4 到 57 个字不等；
  - 截图识字的聊天用的是实验版看图模型。
- 现在：
  - 回复用新的「回复和看图模型」，默认 `deepseek/deepseek-v4.1-flash`（DeepSeek 9 月 10 日发布的最新款，快、便宜、自己就能看图），不先思考；有的模型不能关掉思考，就只想一点点。
  - 边写边显示：判断和每条草稿写完一条出一条，深思也一行一行出。
  - 每次调用都走这个模型最快的服务商（模型名带 `:nitro`、`:floor` 的按它自己说的走）。
  - 「工具 → 模型」分成「深思和学习」和「回复和看图」两个，各有测试按钮，会显示用了几秒。没改过旧看图模型的会自动换成新默认。
  - 写档案这种长调用也因为边写边传，不再长时间干等在连接上。
- 成人内容（18+），默认关，在「设置 → 行为」里打开：
  - Ta 已经在聊性、明显在调情并且乐在其中，或者你们是恋人、以前就这样聊过时，草稿可以同样暧昧、大胆甚至露骨；
  - 尺度跟着对方走，不会比 Ta 表现出来的更进一步；
  - Ta 说不、犹豫、岔开话题，或者有一点可能未成年，就完全不写；
  - 打开后，深思也会直接说出性方面的潜台词。

## 6.7.2

**Reply drafts that fit the moment.** In Soul, the strip of quick replies above the reply box
(下午好, 礼物, 桌球, 比心, 猜拳) was read as messages. The newest thing "they" had said was
「下午好」, so the drafts answered it with 「下午好」「歇会」「嗯」 and the deep read said to reply
下午好. Now none of these is read as a message:
- rows of short texts side by side (quick replies, toolbars, reactions);
- anything at or below the reply box, wherever the keyboard has pushed it: a send button, the
  draft you are typing;
- what sits in the title bar: the unread count on the back button, Soul's 加速.

What older versions kept from that strip is dropped from saved histories.
- A message of theirs that ended near the middle of the screen, next to their avatar
  (「不是美女，有什么好看的」), was taken for a centred date divider and skipped. Centred now means
  equally far from both edges.
- Drafts are no longer the same three strategies in 30 characters (catch the feeling, a concrete
  plan, defuse it). The model is told whose turn it is. It first writes one line on the moment,
  shown above the drafts. Then it gives three different things you would actually send, each
  picking up what was just said: no greeting out of nowhere, no bare 嗯, and "give them space"
  means easy to answer, not cold. When your message is the last one, the drafts are follow-ups
  that ask nothing, or the line says to wait.
- Deep reads and drafts see up to 30 lines. For people you have learned, that includes saved
  history from before what is on screen.
- The deep read talks to you about them: its first line is what they care about, not what you
  do. When you spoke last, it says whether to wait or add something.

**Learning survives a dropped connection.** A profile of a few thousand messages failed halfway
with "Software caused connection abort": Android cuts off a background app's network once the
screen goes off. Now:
- the screen stays on while a history is read and while the profile is written;
- each call is tried again after a pause;
- finished stretches are saved as they land;
- a write that still loses its connection carries on by itself when you are back in that chat.

Errors like this one are now described in plain words.

中文：
- 回复更贴合当下。Soul 输入框上方那排快捷回复（下午好、礼物、桌球、比心、猜拳）以前被当成了聊天消息，所以对方「最新说的」是「下午好」，草稿就回「下午好」「歇会」「嗯」，深思也让你回下午好。
- 现在这些都不再当成消息：
  - 并排的一排短字（快捷回复、工具栏、表情回应）；
  - 输入框及以下的一切（键盘推上去也一样）；
  - 标题栏里的字（返回键上的未读数、Soul 的「加速」）。
- 旧版本存进聊天记录里的那排字会被清掉。
- 靠着头像、结尾停在屏幕中间附近的对方消息（如「不是美女，有什么好看的」）以前会被当成居中的日期分隔线而漏掉，现在修好了。
- 回复不再是固定的三种套路。模型先知道轮到谁说话，写一句「判断」（显示在草稿上面），再给三条真的不一样、你真会发的话。
- 最后一句是你说的时，给的是不施压的追发，或者直接建议先等等。
- 深思和回复能看到最多 30 行上下文：学过的人会从存档里补上屏幕之前的记录。
- 深思的第一句说的是 Ta 在意什么，不再说成你。
- 写档案不怕断网。以前写到一半会报「Software caused connection abort」，因为锁屏后 Android 会断掉后台应用的网络。现在：
  - 读记录和写档案时屏幕保持常亮；
  - 每次调用失败会隔一会儿重试；
  - 写好的部分随写随存；
  - 还是断了的话，回到这个聊天会自动接着写。

## 6.7.1

**Updates install over the previous version from now on.** Every release until now was signed
with a key made for that build alone, so Android refused to install one over another ("App not
installed") and the only way forward was to uninstall, losing the saved keys and people memory.
From 6.7.1 on, all releases are signed with one fixed key and update in place.

Coming from 6.7.0 or earlier, this needs one last uninstall: in the old version, Tools → Backup →
Export first; uninstall; install 6.7.1; Tools → Backup → Import, add the keys again and switch the
accessibility service on. Same features as 6.7.0.

中文：从 6.7.1 起所有版本都用同一个固定签名，以后新版可以直接覆盖安装升级。之前每个版本的签名都不一样，所以会提示「App not installed」，只能卸载重装。从 6.7.0 或更早的版本升级还需要最后一次卸载：先在旧版「工具 → 备份」导出，卸载，装 6.7.1，再「导入」，重新填 Key、打开无障碍服务。功能和 6.7.0 相同。

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

**Soul names.** Soul labels every avatar "Souler"; with a name the app could not read (like
"..."), that label was taken instead, and every such Soul chat became one person called Souler.
Generic labels (Souler, 对方, 用户, …) are never a name now; a name made only of marks ("...",
"。") counts where the app gives text; hidden views are no longer read. Debug mode shows the title
bar's texts and avatar labels, so a misread name is easy to explain. If People has a "Souler"
from before, forget it: each chat gets its own record now.

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

中文：「学习此人」现在会翻完你们的全部聊天记录（不再限 60 页，翻得更快，大约一秒一页），存在手机上，之后边聊边补；再学习只读新增的部分。档案按全部记录分批写成，详细得多：Ta 是你的什么人、有多亲近、Ta 是谁、相处方式、Ta 和你各自怎么说话（附原话）、喜好、常聊的事、梗、重要的事、雷区、Ta 难过时怎么办。回复草稿会带上完整档案和最多 8 段你以前对 Ta 的真实回复，写得像你本人而不是模板。关系和亲近程度分开：关系好不等于是恋人；知道关系后直接告诉 Jev，不再让它猜，不会再把好朋友判成恋爱。「恋爱或亲密关系」改名「恋人或伴侣」，没有背景时不再默认是亲密关系。名字是 emoji 的联系人：能读到文字的应用直接认；截图识字的（微信、Telegram）按头像认，并用标题栏上那个 emoji 的小图来标，不再显示代码；Ta 发来消息后，从通知里自动拿到带 emoji 的真名（「欧欧」也会补成「欧欧🌸」）；⋯ → 改名可以手动起名；「对方正在输入…」不再被当成名字。Soul 给所有头像都标着「Souler」，以前认不出名字时就拿它当名字，结果所有这样的 Soul 聊天都成了同一个人「Souler」；现在这类通用标签不会再当成名字，只由符号组成的名字（如「...」）也能认，隐藏的控件不再读取；人物记忆里之前的「Souler」可以忘掉。学习后停在旧记录页时不再拿旧消息去判断。注意：学习会把和这个人的全部聊天记录发给你的 OpenRouter 模型。

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
