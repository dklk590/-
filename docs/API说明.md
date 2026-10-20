# AI 接口配置与提示词

本程序调用兼容 OpenAI **Chat Completions** 的非流式接口。日常记忆、关系联想、拼写、系统 TTS 和复习计划不需要 AI；阅读生成、译文评价、AI 学习包批量生成和连接测试才发出 API 请求。用户自行提供服务商地址、API Key 和模型名称。

## 在设置页填写

| 项目 | 示例或说明 |
|---|---|
| API 地址 | `https://api.openai.com`、`https://api.openai.com/v1` 或 `https://api.openai.com/v1/chat/completions` |
| API Key | 服务商签发的密钥；不能填写 ChatGPT 网页登录密码 |
| 模型 | 服务商实际支持的模型标识；没有预设模型，避免错误收费 |
| 超时 | 默认 45 秒，可设 10–120 秒 |
| 本地 HTTP 开发 | 默认关闭；开启后仅允许 `localhost`、`127.0.0.1`、`10.0.2.2`。其中 `10.0.2.2` 是 Android 模拟器访问电脑本机服务的地址 |

地址补全只执行一次：根域名补 `/v1/chat/completions`；`/v1` 补 `/chat/completions`；完整 `/chat/completions` 保留。自定义前缀例如 `https://example.com/openai/v1` 会补成 `https://example.com/openai/v1/chat/completions`，不会再插入第二个 `/v1`。末尾斜杠会去掉，查询参数原样保留。带用户名、密码、片段、异常端口或路径穿越的地址会被拒绝。

Key 存在 Android 私有设置中，以 AES-256-GCM 加密；加密密钥由 Android Keystore 保管。设置界面读取的公开 JSON 没有明文 Key，只有 `hasApiKey`。保存时 Key 留空会保留旧 Key；“清除 Key”才删除。程序不输出 Key、请求正文或服务商错误响应到日志，拒绝跟随 HTTP 跳转。卸载应用会丢失密钥和本地学习记录。设备权限受破坏时，此保护不能替代系统安全。

## 学习流程

1. 在阅读阶段选取 3–8 个已学复习词，选择长短、难度和 0–8 个额外目标生词，点击生成。程序将词的拼写、词性和中文义项发给你配置的服务商，生成连贯英文阅读及英文问题。短篇为 80–140 个英文词，长篇为 260–420 个英文词；基础、标准、挑战三档控制句式和信息密度，挑战最高只略高于六级。
2. 程序检查每个目标词确实出现在正文中，而非仅出现在模型自报的列表中；未覆盖、重复、格式不完整或包含中文提示的结果会提示重试。首次阅读不会显示参考译文。
3. 先写自己的中文翻译，再提交评价。评价请求发送英文原文、目标词和你的译文，返回中文反馈、参考译文、纠正依据和语境词义。
4. 评分是学习反馈，不是正式六级成绩，也不代替本人对记忆熟练度的判断。若遇到 AI 理解错误，可保留原译文、参考原文，换用其他模型重评。

API 请求可能产生服务商费用；连接测试也发出一次很小的请求。失败后不自动重试，避免隐性重复费用。超时并不保证服务商没有收到请求，手动重试可能产生第二次计费。请勿在译文输入个人敏感信息；提交给你配置的服务商前，可删除此类信息。

## 实际请求参数

```json
{
  "model": "你配置的模型名称",
  "stream": false,
  "temperature": 0.35,
  "max_tokens": 2200,
  "messages": [
    {"role": "system", "content": "assets 中的对应提示词全文"},
    {"role": "user", "content": "本次任务的 JSON 数据"}
  ]
}
```

兼容性以该协议为准。没有发送 `response_format`，适配部分未实现 JSON mode 的兼容服务。仍要求提示词输出完整 JSON，客户端会严格校验类型、长度、评分范围和引用片段。某些模型不接受 `temperature` 或 `max_tokens`，会返回 400；请使用支持以上 Chat Completions 参数的模型，或在源码 `AiClient.chat()` 中按该服务商官方文档调整。程序没有伪造“AI 已连接”或离线模拟评价。

## 已准备的提示词

| 文件 | 任务与约束 |
|---|---|
| `app/src/main/assets/prompts/generate_reading.txt` | 短篇 80–140 / 长篇 260–420 词；覆盖复习词与指定额外生词，三档难度；禁止提前泄露全文中文翻译和答案 |
| `app/src/main/assets/prompts/evaluate_translation.txt` | 对照原文评价中文译文；接受意思正确的不同表达；准确引用原文与译文，不凭空添加问题；解释语境义项和纠正依据 |
| `app/src/main/assets/prompts/generate_learning_packs.txt` | 按完整字典义项生成自然例句、译文、用法和三层选择题；每层四项且仅一项正确，禁止使用真实子义项作为干扰 |

提示词是真正的运行输入，修改文件并重新构建 APK 即可生效。词汇义项和用户译文按数据传入，不作为提示词指令执行；客户端仍会校验返回结果，降低模型随意改变格式的影响。

阅读输出：

```json
{"title":"English title","text":"English passage","used_words":["review1","review2","review3"],"new_words":[{"word":"extra_word","meaning_in_context":"本段所用义项"}],"length":"short","difficulty":"standard","questions":["English comprehension question?"]}
```

评价输出：

```json
{"score":85,"summary":"中文评价","reference_translation":"完整中文参考译文","corrections":[{"source_span":"原文连续片段","user_span":"译文连续片段，漏译用空字符串","issue":"问题","correction":"修正译法","explanation":"依据"}],"word_notes":[{"word":"目标词","meaning_in_context":"本段义项","note":"搭配或辨析"}],"next_tip":"下一步回忆建议"}
```

模型内容必须是单独 JSON 对象；允许包在一个完整的 `json` 代码块中，不接受代码块以外夹杂解释。读取响应限制为 2 MiB；网络连接和读取均有超时。引用片段须确实来自本次原文和译文，词义提示须属于本次目标词。只有校验通过的结果才显示。

## 常见错误

| 提示 | 处理 |
|---|---|
| 401 | 检查 Key 是否正确、是否已失效 |
| 403 | 检查服务商地区限制和模型权限 |
| 404 | 检查最终接口地址和模型标识 |
| 429 | 检查余额、频率或配额，稍后手动重试 |
| 5xx | 服务商暂时失败，稍后重试 |
| 跳转被拒绝 | 填写服务商给出的最终 HTTPS 地址，不用会跳转的网页地址 |
| 输出被截断 | 换支持足够输出长度的模型，或调整输出预算与提示词 |
| 模型 JSON 格式不正确 | 换更严格遵循 JSON 指令的兼容模型 |
| Key 无法解密 | 先清除已保存 Key，再重新填写和保存 |
| 网络或证书错误 | 检查网络与 HTTPS 证书；程序不会绕过证书验证 |

## 开发接口

```java
SecureSettings settings = new SecureSettings(context);
JSONObject publicSettings = settings.loadPublic(); // 不含 apiKey
JSONObject internalSettings = settings.loadInternal(); // 只在需要发请求时读取
settings.save(new JSONObject().put("apiBaseUrl", "https://example.com/v1"));
AiClient client = new AiClient(context);
// 以下三种方法均需在后台线程调用，不阻塞主界面。
JSONObject reading = client.generate(internalSettings, targetWords, novelWords,
  new JSONObject().put("length", "long").put("difficulty", "standard").put("newWordCount", novelWords.length()));
JSONArray packs = client.generateLearningPacks(internalSettings, uncachedWords);
JSONObject evaluation = client.evaluate(internalSettings, reading, userTranslation);
JSONObject connection = client.probe(internalSettings);
```

`targetWords` 为 3–8 项 JSON 数组，每项含 `id`、`word`、`meaning`、`pos`。`novelWords` 为原生侧按学习状态选出的 0–8 项同结构数组，数量应精确等于 `config.newWordCount`；复习和额外目标集合不能重叠。旧 `generate(settings, targetWords)` 重载默认短篇、标准难度、零个额外目标生词。设置字段为 `apiBaseUrl`、`apiKey`、`apiModel`、`apiTimeoutSec`、`allowLocalHttp`。学习设置字段为 `newPerDay`、`sessionSize`、`ttsRate`、`ttsLocale`、`reminder`、`reminderHour`、`reminderMinute`。保存省略的字段保留原值，不接受数值字符串替代数值。失败抛出可显示的异常，不会写入虚假成功状态。

官方协议和安全参考：

- [OpenAI Chat Completions](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)
- [Android AES-GCM KeyGenParameterSpec 示例](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec)

## v1.1 阅读配置与批量学习包

额外目标生词由程序从学习状态中预选，AI 不得自行换词。`new_words` 只表示本次额外指定的目标词集合，并不保证整篇文章除此之外没有用户未知的词。客户端检查两集合的数量、成员、完整词形在正文出现、长度标记与英文词数；这些检查无法证明文章难度、自然程度与所有语义永远正确。新增词的 `meaning_in_context` 用于点击提示，不是全文中文译文。

AI 学习包使用显式的“生成当前及接下来词”按钮，一次最多 8 个未缓存词。仓储保存成功生成的例句及三层题目，已缓存项重复学习直接读取，不自动发重复收费请求。客户端按词典计算简短 `choiceMeaning` 并随请求发送，正确选项必须原样使用该大意。学习包的 `meanings` 仍复制完整字典来源，不接受 AI 删改。三层只考大意，近义解释不可互为干扰项；完整释义不附加在选项或初始题面中。

各层四个中文选项，一正三干扰。提示词要求干扰项逐步接近，但不允许目标词真实子义项、近义改写、可接受引申或部分正确释义作为错误项；找不到无歧义的近项就降低接近程度。程序还会拒绝重复选项、完整正确义项遗漏、可解析真实子义项的直接重叠以及三层完全相同干扰集合。近义语义无法靠字符串校验完全证明，发现争议题应以原字典为准，重新生成或使用本地材料。

学习包响应：

```json
{"packs":[{"word_id":123,"word":"adapt","meanings":["完整原字典中文义项"],"example":{"en":"Natural example containing adapt.","zh":"例句中文翻译","usage":"本句义项与常见搭配"},"tiers":[{"level":1,"question":"选择这个词的大意。","options":[{"id":"A","text":"输入choiceMeaning原文"},{"id":"B","text":"错误中文义项"},{"id":"C","text":"错误中文义项"},{"id":"D","text":"错误中文义项"}],"answer":"A","explanation":"一句话说明大意"}]}]}
```

以上示例仅展示单层形状；实际响应每词必须恰好 3 层。接口不能自行缓存，调用方需将 `generateLearningPacks()` 返回的完整数组交给仓储 `saveLearningPacks()`。读取现有缓存是否需要更新由仓储处理。短篇阅读预算 2200 tokens、长篇 3500，译文评价按长短为 3000/5000，学习包按每批词数提升预算、上限 14000；实际费用以服务商计费为准。


## v1.4 语境学习

新增本机桥接：`contextCreate(wordIds)`、`contextGet(sessionId)`、`contextVisit(sessionId,sentenceId)`、`contextReveal(sessionId,sentenceId)`、`contextWord(sessionId,sentenceId,surface)`、`contextGrade(sessionId,wordId,grade)`。课程选择1–6个实际词库词条。点词和整句展开由数据库记录；评分不信任界面传入的提示标记，读取课程本身的提示记录。原选择题的`review`协议不变。

显式AI动作：`contextGenerate`为本组生成不同语境，`contextSentenceAi`为当前原句补充译文、结构和朗读提示，`contextWordAi`为所选原词讲解原形、实际词性、语境义、用法和词形。已缓存的同句同词讲解直接返回；新生成语境会清空旧句子的点词列表，保留先前的目标词提示证据。恢复备份期间不接受尚在运行的AI任务交叉写入。

提示词分别为`generate_context_lessons.txt`、`explain_context_sentence.txt`、`explain_context_word.txt`。材料每句必须真实覆盖至少一个目标词；AI材料要求每个目标词至少覆盖两个不同情境，重复句、伪造词条编号、正文夹带中文和漏掉目标词会被拒绝。输入词及已有词形来自原词库，AI不能替换权威词条。整句解析必须回显原句，单词解析必须回显句中实际词，中文释义与解释均有长度限制。

课程、词语讲解、已读状态和逐词评价随`contextSessions`一起备份；导入旧备份时该字段可缺省。API地址和Key仍不进入学习备份。
