# Changelog

APKs for every version are on the [releases page](https://github.com/Neoo-Blue/vibecheck/releases).
Each section below is also that release's notes: the Release workflow publishes the section whose
heading matches the tag.

## 6.8.5

- **A second model stands in.** When the model asked fails in a way another might not (busy, down,
  refusing, cut off halfway, an empty answer), the other model set in Setup is asked the same:
  the reply model for Think, the Think model for replies. It works for deep reads, reply drafts,
  profiles and the profile of you. The card says which answered, and when both fail it gives both
  reasons. A rejected key, an empty balance or no network at all are the same for every model,
  so those are not retried. With both set to one model, DeepSeek V4.1 Flash stands in.
- **Deep reads have room to think.** Their reasoning counts against the answer's length, and 2,500
  tokens could all go on it and leave "the model stopped before answering". They now get 4,000.
- **Rewrite a profile from the kept history, in the app.** A person's page has 「用存的 N 条记录重新分析」
  ("Rewrite from the N kept messages"). It writes their profile again from what is on the phone,
  without opening the chat. The progress shows on the page, and the screen stays on while it runs.
  - "Only what changed" keeps the notes on stretches whose text is the same, for after a fix like
    6.8.4's voice messages.
  - "Everything" notes every stretch again, for after a model change.

中文：
- **备用模型**：用到的模型出错时（忙、挂了、拒绝回答、半路断了、没写出内容），会自动换「设置」里的另一个模型再问一次：深思出错就换回复模型，回复出错就换深思模型。深思、回复、写档案、写关于你的档案都适用。卡片上会注明是谁回答的；两个都失败时会写出两个原因。Key 被拒、余额不足、完全没网这些换模型也没用，不会重试。两个模型选的是同一个时，备用的是 DeepSeek V4.1 Flash。
- **深思有余地思考了**：模型的思考也算在回答长度里，原来 2500 的上限可能全被思考用掉，结果显示「模型没写完」。现在给到 4000。
- **在应用里用存的记录重新分析**：每个人的页面上有「用存的 N 条记录重新分析」，不用打开聊天，直接用手机上存的记录重新写档案。页面上显示进度，写的时候屏幕保持常亮。
  - 「只更新变了的」：内容没变的段落沿用以前的笔记，适合像 6.8.4 修了语音消息之后用；
  - 「全部重做」：每一段都重新整理，适合换了模型以后用。

## 6.8.4

- **Voice messages are not messages.** WeChat shows a voice message's length beside a sound-wave
  icon, and OCR read the two together as 「3" ((」「4"(。」「2"(•」. Kept as messages, those made a
  profile say you "like abstract emoticons and brackets". They are now left out, on screen and in
  kept history. Tap Learn once on that person to rewrite the profile without them; only the
  stretches that change are sent again.
- **An emoji name is shown as its picture on the person's page**, instead of "Unnamed contact"
  with the picture on a line below. A line says why, and where to give them a name in words.

中文：
- **语音消息不再当成消息**：微信在语音消息旁边显示秒数和声波图标，OCR 会把两者连在一起读成「3" ((」「4"(。」「2"(•」。这些被当成消息存下来后，档案就写成了你「爱用抽象表情和括号」。现在屏幕上和存档里都会去掉。装好后在那个人那里点一次「学习」，档案会重写，只有变了的段落会重新发送。
- **表情名字的人，页面标题直接显示那张图**，不再显示「未命名联系人」、再在下面一行放图。标题下面会说明原因，以及在哪里可以给 Ta 起个文字名字。

## 6.8.3

**You can see a profile being written.** Learn used to say 「还在整理上次读到的记录」 and nothing
more for minutes: no telling a slow write from a stuck one, or whether to start again.
- **Progress on the card.** The Learn panel shows:
  - the step: notes on each stretch, merging, or the last step;
  - a bar with the stretches done out of all of them;
  - the messages kept, the time taken, and an estimate of what is left.
  It is redrawn every 15 seconds while it runs, and tapping Learn during a write shows it instead
  of the old line. Stretches are counted as each one comes back, so one slow stretch no longer
  holds the count still.
- **On the person's page too.** In the app the person's page shows the same progress, kept up to
  date, and the page redraws itself with the new profile when it is done. The People list says
  who is being written.
- **Stuck is said, and fixed by Learn.** Ten minutes without any sign of life (longer than a call
  and its retries ever take) and the panel says it looks stuck. Learn then starts the write again,
  keeping the stretches already done; whatever the stuck one does afterwards is dropped.
- **Quotes of mine are not theirs.** When they quote my message, WeChat puts 「my name：my words」
  under theirs, and OCR often can't read my name (「A°😉：女大is no more」). A text like that whose
  words are a message further up the screen is now left out, whoever's name is in front.

中文：
- **学习有进度了**：以前学习时只显示「还在整理上次读到的记录」，好几分钟看不出是慢还是卡住，也不知道要不要重来。
- **卡片上显示进度**：
  - 第几步：逐段整理、合并笔记，或者最后一步写成档案；
  - 进度条：已整理几段、一共几段；
  - 共存多少条、已用多久、预计还要多久。
  写的过程中每 15 秒刷新一次；写的时候再点「学习」会直接显示进度。每段写完就计数，不会因为一段慢就一直不动。
- **应用里也能看**：这个人的页面显示同样的进度，并自动刷新，写完后页面自动换成新档案；「人物」列表里会标出正在写档案的人。
- **卡住会提示，点学习就能重来**：十分钟没有任何进展（比一次调用加上重试还久）会提示「好像卡住了」。这时再点「学习」会重新开始，已写好的段落不会重做，卡住的那次之后的结果会丢掉。
- **引用我的话不再算成对方说的**：对方引用我的消息时，微信会在 Ta 的消息下面显示「我的名字：我的原话」，而 OCR 常常认不出我的名字（比如「A°😉：女大is no more」）。现在只要冒号后面的话和屏幕上方某条消息一样，不管前面是谁的名字，都不算消息。

## 6.8.2

**Steadier, and the card keeps its title.**
- **No more crashing out.** An exception anywhere used to end the whole service: the bubble
  vanished, then came back when Android restarted it. Now:
  - background work logs what went wrong and carries on;
  - so does anything run on the main thread (a screen read, an answer landing, a tap on the card);
  - a deep read or reply whose prompt can't be built says so on the card, instead of dying with
    it still showing "Thinking…";
  - a profile write that fails anywhere ends properly, instead of leaving the person "still
    writing" and the screen kept on;
  - a judgment whose answer never came back is let go after three minutes, instead of holding up
    every later one.
- **The last crash is kept.** Tools → Diagnostics shows what went wrong last, where and when.
  Long-press to copy it.
- **The card no longer loses its title to "…".** An emoji name is shown as a picture cut from the
  title bar. When a photo sat right under the title bar, a thin strip of it was cut out as the
  name, so wide that the title shrank to "…" and the card looked like an old version. The name is
  now the shape at the middle of the bar, and never one that runs off the strip's edge. A picture
  that is not the shape of a name is not kept, and one kept before is thrown away and taken again.
  Pictures are drawn at most four times as wide as they are tall.
- **The card no longer pops open on its own** when a profile write that lost its connection picks
  itself up again: only a read you started opens it.
- **Call records are gone from kept history too.** A 「已取消」 or "Canceled" read as a message
  before 6.8.1 no longer reaches profiles and drafts. The profile is rewritten the next time you
  learn the person.

中文：
- **不再闪退**：以前任何地方出错都会让整个服务退出，气泡消失，等 Android 重启服务后卡片又重新冒出来。现在：
  - 后台出错会记下来，接着运行；
  - 主线程上出错（读屏幕、结果回来、点卡片）也一样；
  - 深思或回复的提示词没拼成时，卡片上直接显示失败，不再一直「思考中…」；
  - 写档案中途出错会正常结束，不会一直显示「还在写」、屏幕也不会一直亮着；
  - 判断三分钟没回来就放弃，不再卡住后面所有判断。
- **记下上次出错**：「工具 → 诊断」里能看到上次出错的时间、位置和原因，长按可以复制。
- **卡片标题不再变成「…」**：emoji 名字是从标题栏截的图。标题栏下面正好有照片时，会把照片的一条边截成名字，宽到标题只剩「…」，卡片看起来就像旧版界面。现在只截标题栏正中间的图形，碰到截取范围边缘的不算。形状不像名字的图不保留，以前存下的错图会删掉重截。图最宽只画到高度的四倍。
- **卡片不再自己弹开**：断网没写完的档案，回到聊天时会自动接着写，这时卡片不会自己弹开；只有你自己点的学习才会弹开。
- **存档里的通话记录也去掉了**：6.8.1 之前被当成消息存下来的「已取消」「Canceled」，不再进入档案和回复。下次学习这个人时，档案会重新写。

## 6.8.1

**Who said what, read right.**
- **Long messages go to the right person.** A message that nearly fills the width sits the same
  from both sides: WeChat's widest bubbles leave equal margins, and the ragged end of the text
  tipped long messages of mine to them. When the margins can't tell, the avatar or bubble drawn
  beside the text now decides, by whichever edge of the window it is clearly nearer. Apps read
  through the node tree use the avatar level with the message. The lines of a link card follow
  its title, where the lines under it used to go to them.
- **Call records are not messages.** WeChat's 已取消, 对方已取消, 已拒绝, 对方无应答, 忙线未接听 and
  通话时长 03:12 are left out, and so are Canceled, Declined, No answer and Call duration in English,
  even when OCR reads the phone icon as a stray mark. So are voice-message lengths such as 5".
- **Quotes.** A quote of their message under my reply (「小雨：周六去爬山吗」) is no longer read as
  something I said.
- **Screenshots.** Deep reads and drafts that get a screenshot are told that bubbles on the right
  are mine and those on the left theirs, and to trust the picture for who said what.

History kept from earlier reads keeps the sides it was read with. To read it again, delete the
kept history on the person's page and learn them again.

中文：
- **长消息不再算错人**：一条消息几乎占满一行时，两边看起来一样。微信最宽的气泡左右留白相同，文字右边又参差不齐，所以我发的长消息常被当成对方说的。现在留白分不出来时，看文字旁边的头像或气泡离哪边的屏幕边缘更近。走节点树的应用看和消息同一高度的头像。链接卡片下面几行跟着标题走，不再算成对方的。
- **通话记录不是消息**：微信的「已取消」「对方已取消」「已拒绝」「对方无应答」「忙线未接听」「通话时长 03:12」，还有英文界面的 Canceled、Declined、No answer、Call duration，都不再当成消息，电话图标被识别成乱码时也一样。语音消息的秒数（如 5"）也不算。
- **引用**：回复下面引用的 Ta 的原话（「小雨：周六去爬山吗」）不再当成我说的。
- **截图**：带截图的深思和回复会被告知：右边的气泡是我发的，左边是 Ta 发的，谁说的以截图为准。
- 以前学习时存下的记录不会自动改正。想重新读，在那个人的页面点「删除聊天记录存档」，再学习一次。

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
