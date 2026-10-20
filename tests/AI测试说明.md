# AI 协议验证

`AiProtocolTest.java` 是可在 Java 17 主机上执行的集成检查，不添加 Android 应用运行依赖。它启动 loopback 假服务，直接调用实际 `AiClient` 网络、提示词读取和返回值校验代码。测试的 API Key 为硬编码的无效测试字符串；不会调用真实 AI 服务，也不会产生服务商费用。

v1.1 已运行结果：**66 项检查通过，33 次本地 mock 请求，跳转目的地收到 0 次请求**。

覆盖短篇/长篇、三档难度、最多 8 个复习词加 8 个额外目标词、精确数量与集合校验、旧重载默认值、提前去除模型附带译文、提交后评价、真实原文/译文引用、分数范围、空译文、缺失目标词、中文泄漏、JSON 代码块和错误 JSON，以及 401/429/503、重定向、响应大小、读取超时、地址与请求头限制。

新增批量学习包验证：按输入词顺序返回、完整字典义项来源不变、三层四选项、缺词/伪造 ID 拒绝、给定简短大意删改拒绝、真实子义项干扰拒绝、重复选项/重复层拒绝、例句没有完整目标词拒绝、完整义项题与例句单义题混用拒绝、解释引用固定字母位置拒绝（含中文相连的标签），以便界面安全洗牌。

复现需 Java 17、Android 35 的 `android.jar` 及主机测试用的 [org.json](https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar)。该 JAR 仅为主机运行提供 JSON 实现，**不会打进 APK**。Android 自带 `org.json`，正式构建仍没有外部 Java 依赖。

```bash
javac -cp /path/to/json-20240303.jar:/path/to/android.jar -d /tmp/wordweave-ai-test \
  app/src/main/java/com/wordweave/app/ContextRules.java \
  app/src/main/java/com/wordweave/app/VocabularyRules.java \
  app/src/main/java/com/wordweave/app/AiClient.java \
  app/src/main/java/com/wordweave/app/SecureSettings.java \
  app/src/main/java/com/wordweave/app/ModelCatalog.java \
  tests/AiProtocolTest.java
java -cp /path/to/json-20240303.jar:/path/to/android.jar:/tmp/wordweave-ai-test \
  com.wordweave.app.AiProtocolTest "$PWD"
```

Windows 的 classpath 分隔符需由 `:` 改为 `;`。超时检查需要约 10 秒。测试不替代实际服务商兼容性和真机验证；Keystore 加解密、Android 系统 TTS 和通知权限依赖 Android 系统，不能通过普通 Java 主机测试完整验证。


语境学习另见`ContextLearningTest.java`，同样使用本地假服务且不需要真实API Key。测试时把`ContextRules.java`、`ContextLearningTest.java`加入上述编译列表，再运行`com.wordweave.app.ContextLearningTest`。界面验证运行`tests/context_learning_ui.mjs`，与旧界面测试共享测试专用`ui_bridge_fixture.mjs`，测试数据不进入应用词库。
