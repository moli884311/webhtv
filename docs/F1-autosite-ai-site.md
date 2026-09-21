# F1：自动站点（输入网址 → 识别接口 → 加入站点列表）

## Recovery anchor

- **目标**：在「过包名版本测试版」原生**设置页**新增「自动站点」入口（一行 + 弹窗）：用户输入一个视频网站地址（或现成 JSON 配置），App 先自行探测是否是可识别的采集接口（苹果CMS JSON/XML），探测不到再用用户自带的 OpenAI 兼容大模型 API 识别，生成 TVBox 站点配置并加入站点列表；已添加的站点可在弹窗内查看与删除。
- **验收标准**：见第 7 节 9 条。
- **车道/范围**：`standard` → 实施时按第 8 节分阶段，每阶段独立 guard 会话。预期触碰路径：`app/src/main/java/com/moliys/tvbox/AiSite.java`（新）、`AiSiteClient.java`（新）、`AiSiteSetting.java`（新）、`app/src/main/java/com/fongmi/android/tv/ui/dialog/AiSiteDialog.java`（新）、`app/src/main/res/layout/dialog_ai_site.xml`（新）、`app/src/{mobile,leanback}/res/layout/*_setting_enhance.xml`、`app/src/{mobile,leanback}/java/**/SettingEnhance{Fragment,Activity}.java`、各 `strings.xml`。**不动 `MainActivity.java`、`TVBoxNative`、`site-src/index.html`、`site.pak`。**
- **状态**：**设计方案已完成并按用户 4 项答复修订（§11）；APK 事实核对已完成（§4.5）。仍未写任何实现代码。**
- **下一步动作（唯一）**：开始 S1 —— 新建 `AiSiteSetting.java` + `AiSite.java`（key 生成、单文件多站点读写、探测、配置生成与校验）并补本地单元测试。
- **回滚锚点**：本设计文档提交所在 commit（`docs/F1-autosite-ai-site.md`）；实施期各阶段 commit 见第 8 节。

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

本 App 是 WebView 外壳（`com.moliys.tvbox`）包住 FongMi TVBox（`com.fongmi.android.tv`）：网页（`site-src/index.html`）负责工具类页面，原生负责播放与**原生设置页**。既有「采集/弹幕」等入口在网页侧、经 `TVBoxNative` 桥接调原生能力。

**本方案按用户要求把 UI 放在原生设置页**（见 D1），因此不新增网页 tab、不新增桥接 —— 与「采集」页做法不同，这是刻意的。

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

### 方案 C（推荐）：原生探测优先 + 用户自带 Key 的 LLM 兜底 + **原生设置页 UI**

- **探测优先**：先按 URL 形态与响应结构判断，能确定就不调 LLM（省 token、快、离线可用）。
- **LLM 兜底**：探测不出时，把（截断并清洗过的）页面 HTML 交给用户配置的 OpenAI 兼容接口，要求输出受约束的 JSON。
- **UI 落原生设置页**（增强设置加一行 + `AiSiteDialog`，见 D1）；**不改网页、不加桥接**。
- **持久化**：单个 `filesDir/moliys_ai_sites.json` 承载全部 AI 站点，统一由一条配置加载。
- 体积：**0 增长**（纯 Java，无新增原生库/网页资源）。
- 否决 B 的理由同样成立；A 不满足需求。

---

## 4.5 星落 APK 事实核对（2026-09-21，仅本地 `/tmp` 分析，不入库）

对 `/tmp/opencode/xingluo/xingluo-6.0.2.apk`（SHA256 `22b19871…2572`）做**只读事实提取**（DEX 字符串池 / `resources.arsc` / `assets/`），用于确认行为规格。

**边界声明：只提事实，不反编译抄码。** 理由：`libmihomo.so` 是 Go 产物、反不出有意义的源码，且 mihomo 本身开源（MetaCubeX/mihomo）；星落与本仓库同 FongMi 血统，基础部分上游即开源；smali→Java 有损且无许可来源，抄进来等于引入不可维护代码。

### 与 F1（自动站点）相关

| 事实 | 证据（DEX 字符串） |
|---|---|
| 默认 AI 端点 | `https://api.siliconflow.cn/v1/chat/completions` |
| 偏好键 | `aiUrl`/`aiKey`/`aiModel`（另有 `ai_url`/`ai_key`/`ai_model`） |
| 提示词是**多步链式**，每步钉死一个 JSON schema | 列表页 `{"数组":"","标题":"","图片":"","链接":"","详情页链接":""}`；详情页 `{"线路数组":"","线路标题":"","播放数组":"","播放列表":"","播放标题":"","播放链接":"","解析":""}`；首页/分类 `{"站名":"","框架":"","分类":"","分类url":"","首页特例":"","特殊分类链接":""}` |
| 产物形态 | **XBPQ 爬虫规则**（非 maccms JSON）；自检句「你是视频网站解析专家，必须严格按照【XBPQ(小暴脾气)爬虫框架】规则输出」 |
| 输出纪律 | 「只返回JSON不要解释不要markdown」 |
| 规则语法要素 | `{cateId}`/`{catePg}`/`{{线路标题}}`；选择器 `p:div.class`/`p:li.item`/`p:ul[class`；过滤 `[包含:a]`；`【指定/轮询】分类名--规则`；`【首页特例】` |

### 与 F2/F3（去广）相关

| 事实 | 证据 |
|---|---|
| 类名 | `com.fongmi.android.tv.player.AdRuleCollector` + `AdRuleLib` |
| 偏好键 | `adRuleLibUrl`、`adRuleLibUrlText`、`ad_rule_collected` |
| 字符串资源 | `ad_rule_lib_url_title`/`_hint`/`_no_url`/`_saved`/`_sync_failed`/`_loaded_msg`/`_loaded_title` |
| **音频去广规则库在线地址** | `https://m3u8-ad-audio-rules-sync.ccfork.workers.dev/rules.json` |
| 视频去广规则库默认占位 | `example.com/rules.json`（`resources.arsc`） |
| 音频去广机制 | **音纹（音频指纹）探针**，非纯 HLS 切片匹配。文案：「跳过：音纹去广告开关未启用」「探针初始化失败，音纹去广告不可用」「可能这个源的广告指纹没采集过」「命中广告，请求跳转」「广告时长超出允许范围」「广告锚点范围无效」 |
| 含义 | 星落去广 = 规则库同步 + 本地采集(`ad_rule_collected`) + 音纹探针三层。我方现有能力仅播放层 HLS 过滤（§2.6），差距真实存在，F2/F3 需按此重定范围 |

### 与 F4（代理订阅）相关

| 事实 | 证据 |
|---|---|
| 内核 | `libmihomo.so` + `com.fongmi.android.tv.proxy.MihomoManager`（mihomo 开源） |
| 协议支持 | `type: vmess`/`vless`/`trojan`，`proxies=`、`proxies from JSON`（clash YAML/JSON 订阅解析） |
| 内置分流 | 大段 `DOMAIN-SUFFIX,...,DIRECT` + 兜底 `MATCH,XYS_PROXY`（代理组名 `XYS_PROXY`） |
| 注入点 | 内置本地 HTTP 服务：`POST /action?do=proxy_sub`（body `url=<订阅地址>`）→ 返回 `{msg}`；`assets/sub.html`(1313B) 是其 UI 壳；`fetch('/api')` 读日志 |
| 文案 | 「代理地址推送」「只用于推送订阅地址」「请输入代理订阅 URL」「推送代理订阅」「订阅地址不能为空」「节点连接测试失败，未应用代理」「代理不可用」「命中代理[」「已路由至代理」「直播代理（PHP psy1 的 Java 版）用法：/ysp」 |
| 含义 | 星落是**完整 mihomo 内核 + 内置订阅转换服务**；我方是 OkHttp 请求级 HTTP 代理（`ProxySetting.java`），差距是「接入一个内核」，工作量远大于 F1 |

### 未获取到的事实（如实记录，不猜测）

- 星落的 XBPQ 解释器实现 —— 无开源上游、未反编译，**不做**（理由见上）。
- `m3u8-ad-audio-rules-sync.ccfork.workers.dev/rules.json` 的**实际内容与授权许可未核**（未抓取）。F3 实施前必须确认授权与格式，否则只能自建规则库。

---

## 5. 关键设计决策

### D1. UI 位置 = 原生设置页（**用户已确认**，推翻本方案原网页侧设计）

用户明确要求「UI 放在设置里面」，因此**改为原生**：

- 入口行加在**增强设置**页，与既有 `shellProxy`/`customCsp` 同级 —— 这三者性质一致（高级、低频、需要原生能力），且 F4 代理订阅也必须贴着 `shellProxy`。
  - mobile：`app/src/mobile/res/layout/fragment_setting_enhance.xml` 加一行，直接仿 `customCsp` 行（`:257-276`）：`androidx.appcompat.widget.LinearLayoutCompat` + `android:background="@drawable/shape_item"` + 标题 `MaterialTextView` + 右对齐状态 `TextView`（`gravity="end"` + `ellipsize="middle"`）。
  - leanback：`app/src/leanback/res/layout/activity_setting_enhance.xml` 加同一行。
  - 点击处理：`SettingEnhanceFragment.java:96` 同款 —— `mBinding.shellProxy.setOnClickListener(view -> ShellProxyDialog.show(this, this::setText))`。
- 交互放在**新 Dialog**（仿 `dialog_shell_proxy.xml` + `ShellProxyDialog.java`），不新增 Activity/Fragment。
- 导航事实（已核对）：网页「设置」按钮 → `TVBoxNative.openVideoSettings()`（`site-src/index.html:2254`）→ `MainActivity.java:1021` `startFongmiNav(HomeActivity, 1)` → `HomeActivity.java:178` `nav_position=1` = `SettingFragment`（基础设置）；增强设置是**另一个导航位置** `nav_position=3`（`HomeActivity.java:180`）。
- **代价（已计入 §8）**：必须同时改 mobile + leanback 两套布局、两个 Java 文件，并补 `values`/`values-zh-rCN`/`values-zh-rTW` 三份字符串。这是「放设置里」的必然成本。

> 待实现时确认（不阻塞设计）：入口行落「增强设置」（与 `shellProxy` 同页，推荐）还是「基础设置」（`openVideoSettings()` 的默认落点）。推荐前者，保持一致。

### D2. 站点产物只可能是 type 1 或 type 0，**不含 type 3**（星落做法不同，已核对）

- `type:3` 需要可执行的 spider（`jar`/`csp_*`），**LLM 无法凭空生成可运行爬虫**。
- 本方案的承诺边界：AI 负责「找出该站可用的接口与参数」，产出 **type 1（苹果CMS JSON）** 或 **type 0（网页/XML）** 站点。
- 若目标站既无 maccms 接口、也无法用 type 0 表达，**如实报「识别失败」**，不允许伪造一个打不开的站点。这是本设计的诚实性底线。
- **与星落的实质差异（证据见 §4.5）**：星落不生成 maccms JSON，而是让模型输出**「XBPQ(小暴脾气)规则」**——一套带 `{cateId}`/`{catePg}`/`{{线路标题}}` 变量、`p:div.class` jsoup 选择器、`[包含:a]` 过滤语法的**规则驱动爬虫描述**，再喂给它自带的解释器。**本仓库没有这套解释器**，其规则文本对我们不可执行。
- 结论：F1 走「接口发现」路线（可稳健实现、可实测验证）；「规则驱动爬虫解释器」等价于自研 XBPQ，**另立任务 F1-B**，不在 F1 范围内。不因为星落这么做就假装我们也能。

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
- **已知风险（2026-09-21 评估，决定不在 F1 内修）**：全 app 共用的 `OkHttp.getBuilder()`（`catvod/src/main/java/com/github/catvod/net/OkHttp.java:238`）设了 `hostnameVerifier((hostname, session) -> true)` 与 `trustAllCertificates()`，即**不校验 TLS 证书**。因此本功能发出的 AI Key 在主动中间人环境下可被读取，`redact()` 只能保证它不进错误文案。**决定不修**，理由三条：①trust-all 是该 fork 为加载任意第三方采集源而做的既有**全局**决策，改动全局会砸产品，属独立任务；②AI Key 与 `AuthInterceptor` 下其它凭据暴露面完全相同，单给这一路加校验收益低且不一致；③单独 client 若想保留用户已配置的代理，须沿用 `OkHttp.client()` 再 `newBuilder()` 覆盖 TLS，而重置 `hostnameVerifier` 需自建 `TrustManager` 并碰 okhttp internal API（脆弱），不复用则代理用户会调不通（真实回归）。**正确的修法是「全局可校验 TLS + 对坏证书源留显式开关」**，需单独设计评审与用户批准，不在 F1 范围。

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
| `app/src/main/java/com/fongmi/android/tv/ui/dialog/AiSiteDialog.java` + `app/src/main/res/layout/dialog_ai_site.xml` | 设置页入口行的面板：目标地址输入、AI 地址/Key/模型、知情同意勾选、`AI识别` 按钮、已添加站点列表 + 删除。仿 `ShellProxyDialog.java` + `dialog_shell_proxy.xml` |

### 6.2 桥接改动 = **无**（D1 改原生后的简化收益）

原网页侧方案需要 7 个 `TVBoxNative` 桥接方法；D1 改为原生设置页后，弹窗直接调用 `AiSite`/`AiSiteClient`，**不需要新增任何 `@JavascriptInterface`**，也不必碰 `MainActivity.java:403` 的注册处与 `site-src/index.html`。

异步一律仿 `CaiSite` 调用点写法：`new Thread(..., "...").start()` + `runOnUiThread`（`MainActivity.java:932` 附近）。

### 6.3 原生设置页改动（D1 已改为原生，**不动网页**）

| 文件 | 改动 |
|---|---|
| `app/src/mobile/res/layout/fragment_setting_enhance.xml` | 在 `customCsp`（`:257`）附近加一行 `aiSite` + `aiSiteText`，仿 `:257-276` 结构 |
| `app/src/mobile/java/com/fongmi/android/tv/ui/fragment/SettingEnhanceFragment.java` | `initEvent()`（`:71`）加 `mBinding.aiSite.setOnClickListener(view -> AiSiteDialog.show(this, this::setText))`；`setText()`（`:132`）加 `setAiSiteText()` 显示已添加站点数 |
| `app/src/leanback/res/layout/activity_setting_enhance.xml` | 加同一行 |
| `app/src/leanback/java/com/fongmi/android/tv/ui/activity/SettingEnhanceActivity.java` | 同步点击与文案 |
| `app/src/main/res/values/strings.xml` + `values-zh-rCN` + `values-zh-rTW` | `setting_ai_site`、`setting_ai_site_count`、`ai_site_*` 系列 |
| `app/src/main/res/layout/dialog_ai_site.xml` | 新面板（`BaseBottomSheetDialog`，仿 `dialog_shell_proxy.xml`） |

配色走 `@color/white` 等既有主题色 + 复用 `shape_item`，**无新增硬编码颜色**，天然适配 1.0.61 的白天/黑夜调色板。

---

## 7. 验收标准

1. 输入形如 `http://<站>/api.php/provide/vod` 的地址 → **不调用 LLM** 即加入列表，点开能进影视主页并搜到内容。
2. 输入苹果CMS 站首页 `http://<站>/` → 自动发现接口并加入（走快速路径）。
3. 输入一个探测不到的网站地址 + 已配置有效 AI → LLM 返回站点 JSON，经第 D5 节四条校验通过后加入列表，列表可见。
4. LLM 返回非法 JSON / 超时 / 401 / 欠费 → 给出可读错误；**现有站点列表与配置文件完全不变**。
5. 未配置 AI Key → 仅走探测路径；探测失败时提示「需要配置 AI 才能识别该站」，不得静默失败。
6. AI Key 不出现在 logcat、不出现在 `moliys_ai_sites.json`、不出现在仓库任何文件。
7. 已添加站点可删除；删除后列表与内核站点同步（重载后该站点消失）。
8. 白天与黑夜主题下，设置页入口行与 `AiSiteDialog` 配色与 1.0.61 调色板一致（复用 `shape_item` 与既有主题色，无新增硬编码颜色）。
9. mobile 与 leanback 两套设置页均有该入口行，行为一致。

---

## 8. 分阶段实施计划

| 阶段 | 内容 | 验证 | 预估 |
|---|---|---|---|
| S1 | `AiSiteSetting` + `AiSite`：key 生成、单文件多站点读写、探测、配置生成与校验 | 本地单元测试 | ~40 min |
| S2 | `AiSiteClient`：LLM 请求/超时/重试/围栏剥离/JSON 解析、HTML 清洗截断、多步链式提示词 | 本地单元测试（含 fixture 响应） | ~60 min |
| S3 | `AiSiteDialog` + `dialog_ai_site.xml` 面板（含列表、删除、知情同意） | 编译 + 真机手测 | ~50 min |
| S4 | 设置页入口行：mobile + leanback 两套布局/Java + 三份 strings | 编译 + 真机手测 | ~45 min |
| S5 | 升版本 → CI → 下载校验 → 上传 → 真机验收（**无 site.pak 重打包**，因不动网页） | 见第 9 节 | ~30 min |
| S6 | 确认后在其余 5 个版本重复 S1~S5（**用户已要求一起做**，见 §11） | 每版本独立 CI | ~4 h（5 版本） |

每阶段一个独立 `task_guard` 会话与一次提交；S5 完成后追加 docs 记录。

---

## 9. 验证方案

- **本地单元测试**（可跑，最便宜的决定性检查）：
  - key 生成：同一 host 稳定、不同 host 不冲突、且不等于 `moliys_cai`
  - 多站点配置：追加/删除后 JSON 合法且其余站点不丢
  - LLM 响应解析：正常 JSON / ```json 围栏 / 前后夹文案 / 非法 JSON / 缺字段 / `type:3` 拒绝
  - D5 校验：`api` 不可达时必须判失败
- **jsdom + jsmoke**：S1~S5 **不动** `site-src/index.html`，因此本方案**跳过网页回归**，不需要 `node --check` / jsmoke
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
| 设置页新增行打乱既有行序或 leanback 焦点 | 新增而非修改；把新行登记进 `reorderItems()`（`SettingEnhanceFragment.java:108`）；leanback 手测焦点链 |

**回滚**：本次为纯新增（新类 + 新 Dialog + 设置页新增行 + 新字符串）。回滚 = revert 对应 commit；遗留的 `filesDir/moliys_ai_sites.json` 是孤立文件，不影响 App，也不进仓库。**无数据库 schema 变更、无原生库变更、无 APK 体积变化。**

---

## 11. 用户已确认项（2026-09-21）

1. **UI 位置 = 原生设置页**（用户：「ui位置在设置里面」）。已据此重写 D1 / §6.2 / §6.3，删除原「网页新 tab + 7 个桥接方法」设计。
2. **AI 服务**：**Key 一律不预置**（用户：「默认的ai服务key肯定不预置啊」）。API 地址与模型名作为**可改的默认占位**预填，参照星落实测值：`https://api.siliconflow.cn/v1/chat/completions` + `Qwen/Qwen2-7B-Instruct`。
3. **多版本范围 = 一起做**（用户：「一起做吧」）。即「过包名版本测试版」跑通后接着覆盖其余 5 个版本，见 §8 S6。
4. **APK 取材方式**（用户：「能从星落apk反编译找源码就找，别的地方找不到的apk里面有」）：
   - **接受**：从 APK **提取事实**（§4.5，本轮已执行）。
   - **不接受**：反编译抄源码 —— 理由见 §4.5（Go 产物反不出、同 FongMi 血统上游本就开源、smali 产物有损无许可）。
   - 若某个事实确实只在星落里，请在对应功能确认时指名，按「提事实」口径处理。

**剩余待定（不阻塞 S1 开工）**：入口行落「增强设置」（推荐，与 `shellProxy` 同级）还是「基础设置」（`openVideoSettings()` 的默认落点）。
