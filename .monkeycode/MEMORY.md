# User Instruction Memory

This file records user instructions, preferences, and teachings for reference in future interactions.

## Format

### User Instruction Entry
User instruction entries should follow this format:

[User Instruction Summary]
- Date: [YYYY-MM-DD]
- Context: [Mentioned scenario or time]
- Instructions:
  - [Content of user teaching or instruction, described line by line]

### Project Knowledge Entry
Entries discovered by the Agent during task execution should follow this format:

[Project Knowledge Summary]
- Date: [YYYY-MM-DD]
- Context: Discovered by Agent while performing [specific task description]
- Category: [Operations & Deployment|Build Methods|Testing Methods|Troubleshooting & Debugging|Workflow & Collaboration|Environment Configuration]
- Instructions:
  - [Specific knowledge points, described line by line]

## Deduplication Strategy
- Before adding a new entry, check for similar or identical instructions.
- If a duplicate is found, skip the new entry or merge it with the existing one.
- When merging, update the context or date information.
- This helps avoid redundant entries and keeps the memory file tidy.

## Entries

[Project Knowledge Summary]
- Date: 2026-09-21
- Context: Discovered by Agent while fixing the 1.0.64 CI build failure (unreported exception JSONException in AiSiteSelfTest)
- Category: Build Methods / Troubleshooting & Debugging
- Instructions:
  - 本机没有 gradle 缓存（`/opt/android-sdk` 只有 `platforms/android-34`，无 build-tools/NDK），改完 Java 代码无法本地 gradle 编译，只能等 fork CI。为了在推送前拦下编译错误，用单文件 `javac` 做检查。
  - **检查编译期受检异常必须只用 `android.jar`**，`-cp` 里不要放 `/tmp/opencode/jars/org-json.jar`。两份 `org.json` 的 `JSONException` 基类不同：`android.jar` 里 `extends Exception`（受检，会报 `unreported exception JSONException`），`org-json.jar` 里 `extends RuntimeException`（非受检，错误被静默放过）。`org-json.jar` 仅用于 harness 运行期（`android.jar` 里的 org.json 是 `Stub!`，`put` 一调用就抛）。
  - **文件一旦出现 `cannot find symbol` / `package X does not exist`，javac 会跳过该类的流分析**，异常检查随之失效。所以被检查的文件依赖必须全部可解析：用 `-sourcepath` 指向桩目录，并把 `catvod/src/main/java/com/github/catvod/crawler/Spider.java`、`SpiderNull.java` 真源码一起编译。
  - 可复用脚本：`/tmp/opencode/f1r2/check-moliys.sh`（已固化上述两点）。判据是「受检异常错误数必须为 0」，其余缺依赖符号属正常。
  - `org.json.JSONObject.put(...)` 在真机/CI 上是受检异常，写 JSON 构造代码必须 `try/catch`；本仓库惯例是 `catch (Throwable e) {}` 吞掉，返回空串或 null，不要向上抛。
