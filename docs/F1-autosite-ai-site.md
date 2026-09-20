# F1：自动站点（输入网址 → 识别接口 → 加入站点列表）

## Recovery anchor

- **目标**：在「过包名版本测试版」WebView 外壳里新增「自动站点」页：用户输入一个视频网站地址（或现成 JSON 配置），App 先自行探测是否是可识别的采集接口（苹果CMS JSON/XML），探测不到再用用户自带的 OpenAI 兼容大模型 API 识别，生成 TVBox 站点配置并加入站点列表；已添加的站点可在页面内查看与删除。
- **验收标准**：见第 7 节 8 条。
- **车道/范围**：`quick-fix` → 实施时按第 8 节分阶段，每阶段独立 guard 会话。预期触碰路径：`app/src/main/java/com/moliys/tvbox/AiSite.java`（新）、`AiSiteClient.java`（新）、`MainActivity.java`、`site-src/index.html`、`app/src/main/assets/site.pak`（重打包产物）、各 `strings.xml`。
- **状态**：**设计方案待用户批准，尚未写任何实现代码。**
- **下一步动作（唯一）**：用户确认第 11 节的 3 个待定项后，开始 S1（原生 `AiSite.java`）。
- **回滚锚点**：本设计文档提交所在的 commit（`docs/F1-autosite-ai-site.md` 新增）；实施期各阶段 commit 见第 8 节。

---

## 1. 功能来源与性质说明

功能形态参照用户提供的 **星落 6.0.2**（包名 `xinghe.tv`，versionCode 6120）APK 的「自动站点」页，其 UI 要素为：输入视频网站地址或 JSON 配置、API 地址（OpenAI 兼容）、AI Key、AI 模型名称、`AI识别` + `确定` 两个按钮、下方「已添加站点」列表。

**性质界定（重要）**：

- 星落是**闭源商业化分支**，GitHub 检索 `xinghe+tv+tvbox`、`xingluo+tvbox`、`星落+tvbox` 均为 0 命中，不存在可对齐的开源上游。
- 因此本功能**按行为规格重新实现**，不复制其代码。仅参照其 UI 字段与交互流程（观测级证据）。
- 星落该功能的实现痕迹（用于确认技术路线可行性，不作代码来源）：`classes.dex` 含 `chat/completions`、`siliconflow`、`openai` 字样与 `AI识别` 字符串；`resources.arsc` 含 `自动站点`、`视频网站地址或JSON配置`、`已添加站点`、`AI Key`、`模型名称`；另有 `assets/chaquopy/*`（Python 运行时 ≈16 MB）推测用于抓页/渲染。

---

## 2. 已有实现与本地契约（file:line）

### 2.1 App 形态前提

本 App 是 WebView 外壳（`com.moliys.tvbox`）包住 FongMi TVBox（`com.fongmi.android.tv`）。**用户可见的站点管理 UI 在网页里**，原生只提供桥接方法。星落的三张截图同样带 App 底部导航，说明其对应页面也在其 Web UI 内 —— 与本项目架构一致。

- 网页源码 `site-src/index.html` → `tools/repack_site.py` → `app/src/main/assets/site.pak`（AES 解密，密钥 `SiteKeys.java`）
- 加载：`app/src/main/java/com/moliys/tvbox/MainActivity.java:111,123-137`（`loadSitePak`）
- 本地 HTTP 服务：`app/src/main/java/com/moliys/tvbox/LocalServer.java:181,192-199,222`

### 2.2 站点数据结构（本仓库权威 schema）

`app/src/main/java/com/fongmi/android/tv/bean/Site.java`：

- `key` 44-47（`@PrimaryKey`，Room 列）、`name` 49-51、`api` 53-55、`ext` 57-60、`jar` 62-64、`click` 66-68、`playUrl` 70-72、`homePage` 74-76、`chromeMode` 78-80、`webHomeChrome` 82-84、`extensions` 86-88
- `type` 90-92（null→0）；语义见 `app/src/main/java/com/fongmi/android/tv/api/SiteApi.java:50,72,102-104,156,165,225-226`：**3=spider/jar，1=json cms（苹果CMS），0=网页/XML**
- `hide` 94-96、`indexs` 98-100、`timeout` 102-104、`searchable` 106-108（Room 列）、`changeable` 109-111（Room 列）、`quickSearch` 112-114、`categories` 116-118、`header` 120-123、`style` 125-127
- 解析入口 `objectFrom(JsonObject, Site)` 157-168；查找 `get(key,name)` 170-175、`findAll()` 177-179
- 相等性 `equals` 458-463 **仅按 `key`**

注意：**`filterable` 字段在本分支不存在**（全仓 0 命中），生成的配置不要带。

### 2.3 配置加载与生效链路

`app/src/main/java/com/fongmi/android/tv/api/config/VodConfig.java`：

- 单例 `Loader.INSTANCE` 320-322；站点集合 `sites` 42、`getSites()` 210、`setSites()` 214
- `load(Config, Callback)` 71、`load(Config)` 117、`parseConfig` 156、`initSite` 194-200（`Site.objectFrom` 197）
- 加载成功事件 `ConfigEvent.vod()` 113；切主站 `setHome` 287 → `RefreshEvent.home()` 289

`app/src/main/java/com/fongmi/android/tv/bean/Config.java`：`create(0,url,name)` 62/66/70、`find(String,String,int)` 114、`insert` 251、`save` 257、`update` 263（同时 `Prefers.put("config_"+type, url)`）。

**现成同型实现（最重要）**：`app/src/main/java/com/moliys/tvbox/CaiSite.java`

- `detectType(api)` 25-35：请求一次，按首字符 `<?xml` / `<rss` 判 type 0，否则 type 1
- `buildConfig(name, api, type)` 38-56：生成 `{"name":..,"sites":[{key,name,type,api,searchable:1,quickSearch:1}]}`
- `write(context, json)` 59-73：写到 `filesDir/moliys_cai.json`，返回 `file://` 地址
- 桥接调用方：`MainActivity.java:932 openCaiSite`（内部 `Config.find(url, siteName, 0)` 956 → `VodConfig.load(cfg, cb)` 957 → 成功后 `openVideoHome()`）
- **局限**：`SITE_KEY` 硬编码为 `moliys_cai`（17 行），**多站点会 key 冲突**；且只支持单站点。F1 必须新写，不得复用其 key。

站点注入注册表（另一可选路线）：`app/src/main/java/com/fongmi/android/tv/setting/CustomCspSetting.java`（目录 `TV/CustomCsp`、`registry.json` 41-42,543-545、前缀 `__custom_csp_`、`inject(List<Site>)` 381-392；UI `app/src/main/java/com/fongmi/android/tv/ui/dialog/CustomCspDialog.java`）。本方案不采用，理由见第 5 节。

### 2.4 网络与 JSON

- `catvod/src/main/java/com/github/catvod/net/OkHttp.java`：`string(url[,timeout])`、`newCall(url[,body][,tag])`，默认超时 `TIMEOUT = 30`（41）
- 调用范例：`MainActivity.java:945`（`OkHttp.string(SITE_API, DETECT_TIMEOUT)`）、`BaseConfig.java:141`、`DanmakuApi.java:44,51`
- **无 POST JSON 封装**，需自建 `RequestBody`（参考 `DanmakuApi.java:44`）
- JSON：`catvod/src/main/java/com/github/catvod/utils/Json.java`（`parse/isObj/isArray/isEmpty/safeString`）、`App.gson()`（`App.java:55`）
- 偏好：`com.github.catvod.utils.Prefers`（范例 `SiteHealthStore.java:181,201`）
- HTML 解析：`jsoup 1.22.2`（`gradle/libs.versions.toml:39,97`）经 `project(':quickjs')` 传递到 app 模块（`app/build.gradle:161`）；app 模块现有 HTML 处理仅 `android.text.Html`（`Util.java:129`）

### 2.5 桥接注册与网页入口

- 注册：`MainActivity.java:403`（`TVBoxNative`）
- 现有桥接：`loadInterface` 896、`openCaiSite` 932、`openLiveSource` 985、`openVideoSettings` 1021、`openInterfaceConfig` 1061、`applyDanmakuConfig` 1097
- 网页调用侧：`site-src/index.html:2286`（`window.TVBoxNative.openCaiSite(name, api)`）
- 网页 tab 表：`site-src/index.html:3572` 起（`tabsData`，现有 18 项，含 `{id:"cai",label:"采集"}`）
- 采集面板 HTML：`site-src/index.html:1962-1967`（`<div class="tab-panel" id="cai-panel">`）

### 2.6 既有相关能力（避免重复造）

- **智能去广（F2）已有设计文档**：`docs/P10-mpv-smart-adblock.md`（含「广告帧进入显示前裁剪」章节 121-148），实施 F2 前必须先读。
- **AI/大模型相关代码：全仓 0 命中**，F1 需从零接入。
- DoH 已存在（`setting_doh`，`app/src/main/res/values/strings.xml:678`；`Backup.APP_PREFS` 含 `doh`）。

---

## 3. 最佳实践调研

### 3.1 证据表

| 类别 | 来源（URL / 仓库路径） | 版本/访问 | 等级 | 支持的结论 | 本项目适用性 | 局限 |
|---|---|---|---|---|---|---|
| 官方源码 | `magicblack/maccms10`（苹果CMS v10，GitHub master） | 访问 2026-09-20 | Primary | 苹果CMS v10 是 PHP+MySQL 影视 CMS，仓库根存在 `api.php`；是 TVBox `type:1` JSON 采集接口的通用来源 | 直接决定「探测优先」策略的可行性：maccms 站有稳定可探测的 HTTP 路径 | 仓库 README 未直接给出 `provide/vod` 参数契约，需在实施时以实际站点响应为准 |
| 官方规范 | OpenAI Chat Completions API 参考 `platform.openai.com/docs/api-reference/chat` | 访问 2026-09-20 | Primary | `POST /chat/completions`；body 含 `model`、`messages[]`、`temperature`、`response_format`；`response_format` 支持 `{"type":"json_object"}` 与 `{"type":"json_schema","json_schema":{...}}`；返回 `choices[0].message.content` | 决定 LLM 调用契约与「强制 JSON 输出」手段 | 各兼容服务商（SiliconFlow 等）对 `json_schema` 支持程度不一，需降级到 `json_object` + 提示词约束 |
| 本地源码 | `app/src/main/java/com/fongmi/android/tv/bean/Site.java`、`api/config/VodConfig.java`、`bean/Config.java` | HEAD `f300269e` | Primary | 本仓库站点 schema、type 语义、加载与生效链路（第 2 节） | 方案的落点与验收依据 | 无 |
| 本地源码 | `app/src/main/java/com/moliys/tvbox/CaiSite.java` | HEAD `f300269e` | Primary | 探测 + 建配置 + 写盘 + 加载的完整同型先例 | 可直接沿用其结构；**但其 `key` 硬编码，必须另写** | 仅支持单站点 |
| 观测 | 星落 6.0.2 APK（用户提供，SHA256 `22b19871…2572`） | 2026-09-20 | Secondary | 功能 UI 字段与交互流程（第 1 节）；确认技术路线存在 | 只做形态参照 | 闭源，不作代码来源 |
| 相关项目 | GitHub 检索 `tvbox+ai+site+generator`、`maccms+api+vod+tvbox`、`tvbox+source+generator` | 2026-09-20 | — | **total_count 均为 0** | — | 见 3.2 |

### 3.2 未获取到的证据（如实记录）

- **没有可借鉴的开源实现**：三轮 GitHub 检索均 0 命中，未找到「用 LLM 生成 TVBox 站点配置」的成熟参考项目。这意味着本功能的提示词设计、输出校验策略、失败重试策略**需要自设计**，无法引用既有实践背书。
- **未取得苹果CMS `provide/vod` 的权威参数文档**：`maccms10` 仓库 README 未列出接口参数表。**决定**：不基于猜测写死参数，改为「发一个探测请求，按响应结构（XML 根节点 / JSON 含 `list`）判定可用性」，与 `CaiSite.detectType` 同思路。
- **未实测 SiliconFlow 等兼容服务对 `response_format` 的支持度**。**决定**：设计上以 `{"type":"json_object"}` 为默认，并在提示词中强制「只输出 JSON」；解析层必须能容忍 ```json 代码围栏与前后多余文本。

---

## 4. 方案对比

### 方案 A：不改（维持现状）

用户只能手工刷入第三方接口配置或用「采集」页的固定探测。满足不了「给个网址就自动出站点」的需求。**否决。**

### 方案 B：照搬星落做法（内嵌 Python 运行时 + 抓页渲染）

星落带 `assets/chaquopy`（≈16 MB）+ `libpython3.10.so`，推测用 Python 抓页/渲染后再喂模型。

- 收益：能处理 JS 渲染的重站点。
- 代价：**APK +16 MB 以上**；引入第二套运行时（Chaquopy）的构建与维护成本；本项目已有 quickjs 爬虫引擎，重复建设。
- **否决**：与「精简版要瘦身」的既定目标直接冲突，收益不抵成本。

### 方案 C（推荐）：原生探测优先 + 用户自带 Key 的 LLM 兜底 + 网页 UI

- **探测优先**：先按 URL 形态与响应结构判断，能确定就不调 LLM（省 token、快、离线可用）。
- **LLM 兜底**：探测不出时，把（截断并清洗过的）页面 HTML 交给用户配置的 OpenAI 兼容接口，要求输出受约束的 JSON。
- **UI 落在网页**（`site-src/index.html` 新增 tab），与「采集/弹幕/接口」一致；原生只加桥接。
- **持久化**：单个 `filesDir/moliys_ai_sites.json` 承载全部 AI 站点，统一由一条配置加载。
- 体积：**0 增长**（纯 Java + 网页）。
- 否决 B 的理由同样成立；A 不满足需求。

---

## 5. 关键设计决策

### D1. UI 位置 = 网页侧，不新增原生布局

网页侧已有 18 个 tab 与成熟的面板/表单样式，且站点管理本身就是网页的职责；原生只加桥接方法。避免改 `fragment_setting.xml` / `activity_setting_enhance.xml` / leanback 三套布局与对应 Java。

### D2. 站点产物只可能是 type 1 或 type 0，**不含 type 3**

- `type:3` 需要可执行的 spider（`jar`/`csp_*`），**LLM 无法凭空生成可运行爬虫**。
- 因此 FL 的承诺边界：AI 负责「找出该站可用的接口与参数」，产出 **type 1（苹果CMS JSON）** 或 **type 0（网页/XML）** 站点。
- 若目标站既无 maccms 接口、也无法用 type 0 表达，**如实报「识别失败」**，不允许伪造一个打不开的站点。这是本设计的诚实性底线。

### D3. 持久化用「单个分组配置」，不用 `CustomCspSetting` 注册表

- 采用 `filesDir/moliys_ai_sites.json`，内容 `{"name":"AI 自动站点","sites":[...]}`；`Config.find(url, name, 0)` + `VodConfig.load`，与 `CaiSite` 同路径。
- 未采用 `CustomCspSetting`：那是「自定义 CSP 类型注入」的注册表，带 `__custom_csp_` 前缀与 `MAX_INSERT_INDEX=9` 等专用约束，语义不匹配；用它会把 AI 站点混进 CSP 概念里。
- **`key` 必须唯一**：新站点 key 由 `moliys_ai_` + 目标站 host 的确定性摘要（如 `sha1(host+api)` 前 10 位）生成，避免 `CaiSite` 那种全站同 key 的问题（`Site.equals` 仅比 `key`，同 key 会被视为同一站点）。

### D4. LLM 调用契约

- `POST {用户填的 API 地址}`（如 `https://api.siliconflow.cn/v1/chat/completions`），头 `Authorization: Bearer {用户填的 Key}`、`Content-Type: application/json`。
- body：`model`、`messages`（system 定死角色与输出格式；user 带目标 URL + 截断后的 HTML）、`temperature: 0.2`、`stream: false`、`response_format: {"type":"json_object"}`。
- **超时必须单独放宽**（LLM 远慢于 30 s 默认值），建议 60 s 并允许配置。
- 解析 `choices[0].message.content` → 去除 ```json 围栏 → `Json.parse` → 严格校验（详见 D5）。
- **失败重试 1 次**（仅针对「非合法 JSON」这一种失败），重试时在 user 消息里追加「上次输出不是合法 JSON，请只输出 JSON」。

### D5. 输出校验（必须全过才落盘）

校验规则（任一条不过即判失败，且**不修改现有配置文件**）：

1. `name` 非空、`api` 非空且是 `http(s)://` 合法 URL
2. `type ∈ {0, 1}`
3. 若 `type == 1`：对 `api` 发一次探测请求，响应必须是可解析的 XML（含 `list`）或 JSON（含 `list`/`class`）—— 即 **`api` 必须真的能返回片子列表**
4. 生成的整体配置 JSON 能被 `VodConfig.load` 成功加载（`Callback.success()`）

第 3 条是关键：**不接受「格式对但打不开」的站点**。

### D6. 隐私与密钥

- **AI Key 由用户自己填**，仅存 `Prefers`，**不硬编码、不读环境变量、不写日志、不写进站点配置文件**。
- **知情同意**：AI 识别会把目标站的页面内容发给用户指定的第三方模型服务。首次使用前必须显式勾选「我已知晓并同意」，状态存 `Prefers`，未同意则 `AI识别` 按钮不可用。
- 送出去的 HTML 需清洗：去 `<script>`/`<style>`、去注释、压缩空白、**截断到 100 KB**。

### D7. 探测优先的具体判定（不调 LLM 的快速路径）

1. 输入本身像接口（含 `provide/vod`、`ac=`、以 `.php` 结尾且含 `api`）→ 直接按 maccms 探测
2. 输入是站点首页 → 抓首页 HTML，正则找 `api.php/provide/vod`、`/provide/vod`、`?m=vod` 等线索；命中即直接建站
3. 输入本身是完整 JSON 配置（以 `{` 开头且能解析出 `sites`）→ 直接走 `VodConfig.load`
4. 以上都不命中，且已配置 AI → 走 LLM
5. 以上都不命中，且未配置 AI → 明确提示「需要配置 AI 才能识别该站」

---

## 6. 数据与接口设计

### 6.1 新增原生类

| 文件 | 职责 |
|---|---|
| `app/src/main/java/com/moliys/tvbox/AiSite.java` | 站点 key 生成、单文件多站点读写、探测（复用 `CaiSite.detectType` 思路但独立实现，不修改 `CaiSite`）、生成/校验配置、落盘 |
| `app/src/main/java/com/moliys/tvbox/AiSiteClient.java` | OpenAI 兼容 `/chat/completions` 调用：请求构造、超时、重试、围栏剥离、JSON 解析；HTML 清洗与截断 |
| `app/src/main/java/com/moliys/tvbox/AiSiteSetting.java` | `Prefers` 读写：`moliys_ai_url`、`moliys_ai_key`、`moliys_ai_model`、`moliys_ai_consent` |

### 6.2 新增桥接（`MainActivity` + `TVBoxNative` 注册处 `MainActivity.java:403`）

| 方法 | 入参 | 出参 | 说明 |
|---|---|---|---|
| `getAiSiteConfig` | — | JSON | 返回 `{url, model, hasKey, consent}`；**不回传 Key 明文** |
| `setAiSiteConfig` | url, key, model, consent | boolean | key 传空串表示「不修改现有 Key」 |
| `listAiSites` | — | JSON 数组 | 读 `moliys_ai_sites.json` |
| `aiDetectSite` | url | JSON | 探测/LMM 识别，成功返回 `{ok, site:{...}, source:"probe"|"ai"}`，失败返回 `{ok:false, msg}` |
| `addAiSite` | site JSON | JSON | 追加并落盘 + 重载，返回 `{ok, url}` |
| `removeAiSite` | key | JSON | 删除并落盘 + 重载 |
| `openAiSites` | — | — | 加载并进入影视主页（复用 `openVideoHome()`） |

异步一律仿 `CaiSite` 调用点写法：`new Thread(..., "...").start()` + `runOnUiThread`。

### 6.3 网页侧改动（`site-src/index.html`）

- `tabsData` 增 `{ id: "aisite", label: "自动站点", hideSearch: true }`（`:3572`）
- 新增 `<div class="tab-panel" id="aisite-panel">`，仿 `cai-panel`（`:1962`）
- 表单：视频网站地址或 JSON 配置 / API 地址 / AI Key / AI 模型名称 / 知情同意勾选 / `AI识别` + `确定`
- 列表：「已添加站点」，每项带删除
- 全部复用现有 CSS 变量，天然适配白天黑夜

---

## 7. 验收标准

1. 输入形如 `http://<站>/api.php/provide/vod` 的地址 → **不调用 LLM** 即加入列表，点开能进影视主页并搜到内容。
2. 输入苹果CMS 站首页 `http://<站>/` → 自动发现接口并加入（走快速路径）。
3. 输入一个探测不到的网站地址 + 已配置有效 AI → LLM 返回站点 JSON，经第 D5 节四条校验通过后加入列表，列表可见。
4. LLM 返回非法 JSON / 超时 / 401 / 欠费 → 给出可读错误；**现有站点列表与配置文件完全不变**。
5. 未配置 AI Key → 仅走探测路径；探测失败时提示「需要配置 AI 才能识别该站」，不得静默失败。
6. AI Key 不出现在 logcat、不出现在 `moliys_ai_sites.json`、不出现在仓库任何文件。
7. 已添加站点可删除；删除后列表与内核站点同步（重载后该站点消失）。
8. 白天与黑夜主题下该页面配色与站点一致（复用现有 CSS 变量，无新增硬编码颜色）。

---

## 8. 分阶段实施计划

| 阶段 | 内容 | 验证 | 预估 |
|---|---|---|---|
| S1 | `AiSiteSetting` + `AiSite`：key 生成、单文件多站点读写、探测、配置生成与校验 | 本地单元测试 | ~40 min |
| S2 | `AiSiteClient`：LLM 请求/超时/重试/围栏剥离/JSON 解析、HTML 清洗截断 | 本地单元测试（含 fixture 响应） | ~50 min |
| S3 | `MainActivity` 桥接 6 个方法 + `TVBoxNative` 注册 | 编译 + 真机手测 | ~40 min |
| S4 | `site-src/index.html` 新增 tab/面板/表单/列表 + 主题适配 | jsdom/jsmoke 校验 + 真机 | ~60 min |
| S5 | 重打包 `site.pak` → 升版本 → CI → 下载校验 → 上传 → 真机验收 | 见第 9 节 | ~30 min |

每阶段一个独立 `task_guard` 会话与一次提交；S5 完成后追加 docs 记录。

---

## 9. 验证方案

- **本地单元测试**（可跑，最便宜的决定性检查）：
  - key 生成：同一 host 稳定、不同 host 不冲突、且不等于 `moliys_cai`
  - 多站点配置：追加/删除后 JSON 合法且其余站点不丢
  - LLM 响应解析：正常 JSON / ```json 围栏 / 前后夹文案 / 非法 JSON / 缺字段 / `type:3` 拒绝
  - D5 校验：`api` 不可达时必须判失败
- **jsdom + jsmoke**：网页侧内联 JS 过 `node --check`，并用既有 harness 跑一遍面板渲染（`beforeParse` 注入 `TVBoxNative` 桩）
- **真机**：第 7 节 8 条验收
- **CI**：`./gradlew :app:assembleMobileArm64_v8aRelease --no-daemon`（本地无法编译 Android）

---

## 10. 风险与回滚

| 风险 | 缓解 |
|---|---|
| LLM 输出不可靠，生成打不开的站点 | D5 第 3 条：必须实测 `api` 能返回列表才落盘 |
| LLM 服务商不支持 `json_object` | 默认只用 `json_object` + 提示词强约束；解析层容忍围栏；失败重试 1 次 |
| 把目标站 HTML 发给第三方有隐私争议 | D6 知情同意 + 只发清洗后前 100 KB |
| AI Key 泄漏 | 只存 `Prefers`；桥接只回传 `hasKey`；不打日志 |
| 与既有「采集」页行为混淆 | 新建独立页面与独立文件，不改 `CaiSite` 任何行为 |
| 网页侧改动破坏现有 18 个 tab | 新增而非修改；jsmoke 回归 |

**回滚**：本次为纯新增（新类 + 新 tab + 新桥接 + 新文件）。回滚 = revert 对应 commit；遗留的 `filesDir/moliys_ai_sites.json` 是孤立文件，不影响 App，也不进仓库。**无数据库 schema 变更、无原生库变更、无 APK 体积变化。**

---

## 11. 待用户确认项

1. **UI 位置**：确认「自动站点」做成网页新 tab（与采集/弹幕同级），而不是原生设置页的一行？二选一实现差异最大，本方案按网页侧设计。
2. **AI 服务**：默认预填的 API 地址与模型名用什么？（星落截图是 `https://api.siliconflow.cn/v1/chat/completions` + `Qwen/Qwen2-7B-Instruct`）。Key 必须用户自填，不预置。
3. **多版本落地范围**：F1 先在「过包名版本测试版」落地，确认后再复制到其余 5 个版本？还是本次就一起做？
