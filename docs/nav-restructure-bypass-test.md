# bypass-test 导航重构（站点标签 + 原生入口）任务记录

- 任务类型：站点（site.pak）+ 原生 WebView 壳交互重构
- 作用范围：**仅 `过包名版本测试版`（bypass-test）**，正式版暂不改动
- 前置任务：`docs/license-gate-bypass-test.md`（授权门禁，已上线）

## 1. 需求

1. 删除原站点内「影视」页面（其内容整体移除）。
2. 底部标签「在线影视」改名「影视主页」，点击进入**原生影视首页**（HomeActivity）。
3. 新增底部标签「在线直播」，点击进入**原生直播**（LiveActivity）。
4. 顶部原「模式切换（简单/全能）」按钮位置改为「**设置**」按钮，点击进入**原生 TVBox 设置界面**。
5. 移除原「影视」悬浮入口按钮。
6. 保留机器人授权门禁：**影视主页 / 在线直播 / 设置** 三个入口在未授权时均须拦截并弹出授权面板。

## 2. 站点侧改动（`site-src/index.html`）

| 项 | 改动 |
| --- | --- |
| 顶部按钮 | 新增 `#settingsBtn`；`html.native-app` 下隐藏 `#modeToggleBtn`、显示 `#settingsBtn`；web 版相反 |
| 模式 | native 下强制 `currentMode='full'`（不再有简单/全能切换） |
| 设置按钮 | 点击 → `window.TVBoxNative.openVideoSettings()`（缺失时回退 `openVideo()`） |
| `tabsData` | `online` 标签名 → 「影视主页」(`hideSearch:true`)；新增 `{id:"liveapp",label:"在线直播"}`；保留 `convert`（直播源转换） |
| `showPanel` | `online` → native `openVideo()`，非 native 回退 `renderOnline()`；`liveapp` → native `openVideoLive()` |
| 面板 | `online-panel` 改为「影视主页」进入按钮卡；新增 `liveapp-panel` 进入按钮卡；`renderOnline()` 清空 |
| 图标 | nav-tab `online` → 🎬，新增 `liveapp` → 📺 |
| 版本 | `SITE_VERSION` 3.0.27 → 3.0.28 |

涉及「影视」页面内容的删除：原 `renderOnline()` 内的页面数据渲染整体移除，仅保留进入原生页的入口卡。

## 3. 原生侧改动（`MainActivity.java`）

- `injectVideoEntry()`：移除「影视」悬浮按钮注入逻辑；保留「关于」标签连点 6 次（4s 窗口）打开授权面板的功能。
- 新增私有 `openVideoSettings()` → `startFongmiNav(HomeActivity, nav_position=1)`，进入设置页。
- 新增私有 `startFongmiNav(cls, position)`：构造含 `EXTRA_NAV_POSITION("nav_position")` 的 Intent。
- Bridge (`TVBoxNative`) 新增：
  - `openVideoLive()`：未授权 → Toast + 授权面板；已授权 → `MainActivity.openVideoLive()`（LiveActivity）。
  - `openVideoSettings()`：未授权 → Toast + 授权面板；已授权 → `MainActivity.openVideoSettings()`。
- Bridge `openLive()` 改为显式 `MainActivity.this.openVideoLive()`，避免与同名 Bridge 方法形成递归。
- `openVideo()`（影视主页）保持原有授权拦截。

## 4. 打包与校验

- 站点源目录：`site-src/`（与 `_网页与签名备份/site/` 一致，78 文件）。
- 重打脚本：`tools/repack_site.py --site-dir site-src --out app/src/main/assets/site.pak --manifest-url https://tvbox.moliys.icu/apk/version-moliys.js --no-fetch`
- 校验：解包 `site.pak` 与 `site-src/` 逐文件比对一致；站点内联 JS `node --check` 通过；`MainActivity.java` 花括号/圆括号平衡。
- 版本：`Version.java` CODE 43 / NAME 1.0.42；`app/build.gradle` versionCode 43 / versionName 1.0.42；workflow tag `moliys-1.0.42`。

## 5. 验收清单（真机）

- [ ] 底部标签顺序含：解密工具 / 影视主页 / 短剧 / 直播源转换 / 在线直播 / 关于
- [ ] 顶部为「设置」按钮，无简单/全能切换
- [ ] 无「影视」悬浮按钮
- [ ] 未授权：点 影视主页 / 在线直播 / 设置 均弹出授权面板，不进原生页
- [ ] 已授权：三者分别进入 原生影视首页 / 原生直播 / 原生设置页
- [ ] 关于标签连点 6 次仍可打开授权面板

## 6. 交付记录

- 代码提交：`f12c905cd5f3a0de70dba3686edf9a272f2d57ad`（branch `bypass-test`，fork `moli884311/webhtv`）
- CI：run `35497781938`（commit f12c905c）**success**
- 产物校验：package `com.fongmi.android.tvceshi`，versionCode `43`，versionName `1.0.42`
- APK SHA256：`caea696d0525a5bda7320117ae1ab210583fb2e6c3746b46e16c9ff3364c102c`
- 上传：`https://tvbox.moliys.icu/apk/tvbox-moliys-bypass-test-1.0.42.apk`（141366879 字节，HTTP 200）
- 待办：真机按第 5 节验收；通过后可同步 `过包名版本正式版`

## 7. 第二轮调整（1.0.43）

需求：

1. 进入「设置」必须是单独设置页，**不显示原生底部三个标签**。
2. 「影视主页」同样进原生界面，但**隐藏原生底部标签**（全屏原生影视首页）。
3. **未授权时隐藏** 影视主页 / 在线直播 标签与顶部「设置」按钮（而非仅拦截）；解除授权后重新隐藏。

实现：

- 站点 `index.html`
  - `renderTabs()` 过滤：`window.__moliysLicenseOk === false` 时不渲染 `online`/`liveapp` 两个标签。
  - 新增授权 UI 块（`<script>` 尾部）：`licenseQuery()` 调 `TVBoxNative.isAuthorized()`；`applyLicenseUI(ok)` 控制 `#settingsBtn` 显隐、重渲染标签，并在当前停留在被隐藏标签时切回 `api`。
  - 初始化执行一次，并在 `visibilitychange`（回到前台）与 `focus` 时复查；导出 `window.__moliysApplyLicense` 供原生主动推送。
- 原生 `MainActivity.java`
  - 新增 Bridge `isAuthorized()` 返回 `"1"/"0"`。
  - 新增 `pushLicenseState(WebView)`：调用 `window.__moliysApplyLicense(true/false)`；在 `onResume` 的授权刷新回调与 `refreshLicense` 回调中调用。
  - `openVideoHome()` → `startFongmiNav(HomeActivity, 0, true)`；`openVideoSettings()` → `startFongmiNav(HomeActivity, 1, true)`；`startFongmiNav(cls, position, hideNav)` 传递 `hide_nav` 并按 `NEW_TASK|CLEAR_TOP|SINGLE_TOP` 启动。
- 原生 `HomeActivity.java`（mobile）
  - 新增 `EXTRA_HIDE_NAV = "hide_nav"`；`checkAction()` 处理 `nav_position` 后若带 `hide_nav` 则 `setNavigationVisible(false)`。
  - 新增 `navHidden` 字段（`setNavigationVisible` 同步）；`onBackInvoked()` 在 `navHidden` 时直接 `super.onBackInvoked()`，返回站点页而不是露出带底部标签的原生首页。

版本：`Version.java` CODE 44 / NAME 1.0.43；`app/build.gradle` versionCode 44 / versionName 1.0.43；workflow tag `moliys-1.0.43`。

验收清单（真机）：

- [ ] 未授权：底部无「影视主页」「在线直播」，顶部无「设置」按钮
- [ ] 授权后：三个入口出现；解除授权后立即消失
- [ ] 点「设置」→ 单独设置页，底部无三个标签，返回键回到站点页
- [ ] 点「影视主页」→ 原生影视首页全屏（无底部标签）
- [ ] 「关于」连点 6 次仍可打开授权面板

交付记录（1.0.43）：

- 代码提交：`fb25273b59dcf52b5bc408bdbbd29d25884cd1d0`
- CI：run `35506743359`（commit fb25273b）**success**
- 产物：package `com.fongmi.android.tvceshi`，versionCode `44`，versionName `1.0.43`
- APK SHA256：`972fd87c2f25f6d10876b75dbd5cb203534852d30b39ff5cbc0e2cad493c4a5c`
- 上传：`https://tvbox.moliys.icu/apk/tvbox-moliys-bypass-test-1.0.43.apk`（141367247 字节，HTTP 200）
- 备注：修复了仓库未提交 `site-src` 下中文文件名文件的问题（本机 `git config core.quotePath false`，否则任务守卫暂存会漏掉 CJK 文件名）

## 8. 第三轮修复（1.0.44）

现象：1.0.43 真机上进入「影视主页」「设置」后，原生底部三个标签**仍然存在**；页面背后还显示 TVBox 壁纸（梦幻紫霞）。

原因：`hide_nav` 只在 `checkAction()` 里隐藏一次，之后 `VodFragment` 的 WebHome（网页首页）与其它逻辑会再调用 `setNavigationVisible(true)`（如 `openVod()`、`change()`），把底部标签重新显示出来。

修复（`HomeActivity.java` mobile）：

- 新增 `keepNavHidden` 常驻标志：`setNavigationVisible(visible)` 在 `keepNavHidden` 为真时忽略任何「显示」请求。
- `checkAction()` 收到 `hide_nav` 时置 `keepNavHidden = true`，并把根布局背景设为纯深色 `#0F1115`（覆盖壁纸背景），再隐藏底部标签。
- `keepNavHidden` 写入 `onSaveInstanceState` / `initView` 恢复，避免重建后再显示。

版本：CODE 45 / NAME 1.0.44；`app/build.gradle` versionCode 45 / versionName 1.0.44；workflow tag `moliys-1.0.44`。

说明（待确认）：原生页面无法嵌进网页内部，站点面板里只能放 iframe 网页。若要把影视主页/在线直播放进站点面板，需要提供可嵌入的网页地址。

交付记录（1.0.44）：

- 代码提交：`e9eb0491d220c4fa8dafda8a689729dfaf5584ad`
- CI：run `35511570157`（commit e9eb0491）**success**
- 产物：package `com.fongmi.android.tvceshi`，versionCode `45`，versionName `1.0.44`
- APK SHA256：`b9c38591365d46cb9c688f00f51589f0be0477a7a5654aba1fc167f22ecc3297`
- 上传：`https://tvbox.moliys.icu/apk/tvbox-moliys-bypass-test-1.0.44.apk`（141367247 字节，HTTP 200）

## 9. 第四轮修复（1.0.45）

现象：1.0.44 真机进入影视主页，底部三个按钮仍存在（背景已变为纯深色）。

原因：隐藏动作只挂在 `checkAction()`（由 `VodConfig.load` 回调触发）；若该回调未触发或晚于其它逻辑，隐藏就不会生效。

修复（`HomeActivity.java` mobile）：

- `keepNavHidden` 在 `initView()` 阶段即由 `getIntent().getBooleanExtra("hide_nav", false)` 判定（不再只依赖 `checkAction`）。
- 抽出 `applyKeepNavHidden()`：置纯深色背景 + 隐藏底部标签；在 `initFragment()` 之后、`checkAction()` 内、以及 `onResume()` 中都会执行，确保任何时机都保持隐藏。
- `setNavigationVisible(visible)` 在 `keepNavHidden` 时忽略一切「显示」请求。

版本：CODE 46 / NAME 1.0.45；`app/build.gradle` versionCode 46 / versionName 1.0.45；workflow tag `moliys-1.0.45`。

交付记录（1.0.45）：

- 代码提交：`5ff2fce548b752e4b381487e44b3d0a2440575b8`
- CI：run `35512499746`（commit 5ff2fce5）**success**
- 产物：package `com.fongmi.android.tvceshi`，versionCode `46`，versionName `1.0.45`
- APK SHA256：`0e0f8367527f0fbf55781c3ad3fa79a686a5370a0aecd14b55ea91f4bfcaaf9c`
- 上传：`https://tvbox.moliys.icu/apk/tvbox-moliys-bypass-test-1.0.45.apk`（141383631 字节，HTTP 200）

## 10. 第五轮修复（1.0.46）

现象：1.0.45 真机已确认（关于 = `moliys-1.0.45`），进入设置页后底部 `影视 / 直播 / 设置` 仍存在。

根因：真正的漏点在 `WebHomeChromeController.applyLayout()`（`app/src/mobile/java/.../WebHomeChromeController.java:180`）：

```java
binding.navigation.setVisibility(normal ? View.VISIBLE : View.GONE);
```

该方法由 insets 监听、`refreshLayout()` 等路径频繁触发，每次都会按 chrome 模式重算可见性，因此 `HomeActivity.setNavigationVisible(false)` 与 `keepNavHidden` 会被直接覆盖 —— 之前只在 `HomeActivity` 内部加防护，管不到控制器。

修复：

- `WebHomeChromeController.Host` 新增 `boolean isNavigationForceHidden();`。
- `applyLayout()` 拆成两个判断：`chromeNormal`（原有 chrome 模式语义，仍决定顶部安全区）与 `normal = chromeNormal && !host.isNavigationForceHidden()`（决定底部标签可见性与底部安全区）。
- `HomeActivity` 实现 `isNavigationForceHidden()` 返回 `keepNavHidden`；`applyKeepNavHidden()` 末尾调用 `mChrome.refreshLayout()` 立即按新规则重算。

效果：无论控制器在 `initView` / `checkAction` / `onResume` 之后如何刷新，只要 `keepNavHidden` 为真，底部标签都会被置为 `GONE`。

版本：CODE 47 / NAME 1.0.46；`app/build.gradle` versionCode 47 / versionName 1.0.46；workflow tag `moliys-1.0.46`。

交付记录（1.0.46）：

- 代码提交：`1f3939c4976b071ab80587b02e905e5716cc43dc`（改动 6 文件：控制器/HomeActivity/Version/build.gradle/build.yml/本文档）
- CI：run `35513346301`（head_sha `1f3939c4`）**success**
- 产物：package `com.fongmi.android.tvceshi`，versionCode `47`，versionName `1.0.46`
- APK SHA256：`989804784d5e650a8a5a528757af6c6ecd5cdc833b8cf8bf3254a3bc60e31214`
- 上传：`https://tvbox.moliys.icu/apk/tvbox-moliys-bypass-test-1.0.46.apk`（141383631 字节，HTTP 200）

验证方式说明：Release 构建 `minifyEnabled = !fastRelease`（默认 true），方法名被 R8 混淆，因此 dex 字符串探针无法确认方法存在性（`setNavigationVisible` 等既有方法同样 `MISSING`）。本版证据链为 git 提交 `1f3939c4` 内容核对 + CI run `35513346301` head_sha 一致。

## 11. 采集页「打开站点」改用 WebHome（1.0.47）

需求：采集列表里的「打开站点」不再跳外部浏览器/网页，改为用原生 WebHome 加载该站点首页；授权门禁与「影视主页」一致。

站点侧（`site-src/index.html`）：

- 卡片模板把 `<a class="cai-open" href target="_blank">打开站点</a>` 改为 `<button class="cai-open" data-home="...">`，`s.home` 为空时不渲染该按钮。
- 新增点击委托：存在 `TVBoxNative.openWebHome` 时调用它，否则回退 `window.open(home,'_blank')`（纯网页环境保留原行为）。
- `.cai-open` 补 `font-family: inherit; line-height: 1;` 以匹配 `<button>` 默认样式。
- `SITE_VERSION` 3.0.28→3.0.29；`config.json` 的 `site.version` 3.0.27→3.0.29（页面版本角标来源）。

原生侧：

- `MainActivity`：新增 `openWebHome(String url)`，复用 `startFongmiNav(HomeActivity, 0, true, url)`；`startFongmiNav` 增加带 `web_home_url` extra 的重载。
- Bridge 新增 `@JavascriptInterface openWebHome(String url)`，未授权时 Toast + 弹授权框（与 `openVideo`/`openVideoLive` 同规则）。
- `VodFragment.getHome()` 支持临时覆盖：读取宿主 Activity Intent 的 `web_home_url`，构造一次性 `Site`（key `moliys_web_home`、name 取 host、homePage 取该 URL），**不写入用户配置**；`loadHome()` 与 `RefreshEvent.HOME` 两条路径都走 `getHome()`，因此保持一致。
- `HomeActivity.onNewIntent` 增加 `setIntent(intent)`（否则复用实例时 `getIntent()` 仍是旧的 extra），并在带 `web_home_url` 时 `RefreshEvent.home()` 触发 WebHome 重新加载。

版本：CODE 48 / NAME 1.0.47；`app/build.gradle` versionCode 48 / versionName 1.0.47；workflow tag `moliys-1.0.47`；site.pak 重打（78 文件，SHA256 `abaddf22e4849ac0cf7a9c9cec6a3814c084872bc9f01cc087d616dd55608a6b`）。

交付记录（1.0.47）：

- 代码提交：`c9023103d6ef71fb384bf5be57f710844264b945`（10 文件）
- CI：run `35515003250`（head_sha `c9023103`）**success**
- 产物：package `com.fongmi.android.tvceshi`，versionCode `48`，versionName `1.0.47`
- APK SHA256：`4c9984d3dbaeaa1fbe84ae2ae375ecef1ca9c6f75ba2484664eea237dedffce3`
- 上传：`https://tvbox.moliys.icu/apk/tvbox-moliys-bypass-test-1.0.47.apk`（141383803 字节，HTTP 200）
- 站点校验：3 段内联 JS 全部通过 `node --check`；重打后 pak 内可见 `SITE_VERSION = '3.0.29'`、`cai-open` 按钮模板、`openWebHome` 调用

## 12. 回滚

- 站点：`git revert` 对应提交后由 `site-src/` 重打 `site.pak`。
- 原生：`git revert` 对应提交，或恢复 `MainActivity.java` 中 `injectVideoEntry` 的悬浮按钮分支与 Bridge 方法。

## 13. 采集页「打开站点」改用配置方式加载采集接口（1.0.48）

需求修正：1.0.47 用 WebHome 打开站点仍是网页，用户要求「打开站点」把**采集接口本身**按 TVBox 配置方式加载使用。

关键结论（纠正 1.0.47 之前的误判）：本 App 并不需要自写 spider。`SiteApi` 对 `Site.type` 不是 3(spider)/4(custom) 的站点会直接走苹果CMS直连路径：

- `Result.fromType(type, body)`：`type == 0` 走 XML，其余走 JSON。
- `ac(type)`：`type == 0` 用 `ac=videolist`（苹果CMS XML 列表），其余用 `ac=detail`（苹果CMS JSON）。
- 首页取 `site.getApi()` 原文；分类/详情/搜索分别带 `ac/t/pg`、`ac/ids`、`wd/quick/extend` 参数。
- 实测采集接口（如 `https://www.maoyanzy.com/api.php/provide/vod/`）返回苹果CMS JSON（`code/msg/page/pagecount/list[].vod_id/vod_name/vod_pic/vod_play_from/vod_play_url`），与 `Vod` 的 `@SerializedName` 完全对应。

因此「打开站点」= 生成只含该采集站点的单仓配置并 `VodConfig.load`，站点进入 `VodConfig.getSites()` 后原生 `FolderFragment`/`SearchFragment` 即可正常浏览。

需求：采集列表里的「打开站点」按钮，用该站的采集接口（`s.api`）生成配置并以配置方式加载；授权门禁与「影视主页」一致。

站点侧（`site-src/index.html`）：

- 卡片按钮由 `data-home` 改为 `data-api`/`data-name`，`s.api` 为空时不渲染按钮。
- 点击委托改调 `TVBoxNative.openCaiSite(name, api)`；非 App 环境仅 `alert` 提示，不再打开网页。
- `SITE_VERSION` 3.0.29→3.0.30；`config.json` 的 `site.version` 3.0.29→3.0.30。

原生侧：

- 新增 `com/moliys/tvbox/CaiSite.java`：`detectType(api)`（8s 超时拉取一次，`<?xml`/`<rss` 判为 0 即 XML，否则 1 即 JSON）、`buildConfig(name,api,type)`（单站点 JSON：`key=moliys_cai`、`type`、`api`、`searchable/quickSearch=1`）、`write(context,json)`（写入 `files/moliys_cai.json`，返回 `file://` 地址；内核经内置 Server 的 `/file/` 路由读取）。
- `MainActivity`：新增 `openCaiSite(name, api)`——后台线程探测格式并写配置，主线程 `Config.find(url, name, 0)` + `VodConfig.load(cfg, cb)`，成功后 `openVideoHome()`、失败 Toast。
- Bridge：`openWebHome` 改为 `openCaiSite(String name, String api)`，未授权 Toast + 弹授权框（同 `openVideo`）。
- 回滚 1.0.47 的 WebHome 通道：删除 `startFongmiNav` 的 `web_home_url` 重载、`VodFragment` 的 `getWebHomeOverride()`/相关常量与 `UrlUtil` 导入、`HomeActivity.onNewIntent` 的 `web_home_url` 刷新。1.0.46 的 `WebHomeChromeController.isNavigationForceHidden()` 隐藏底部标签修复保留。

已知行为：加载后该采集接口会成为当前激活接口（写入配置表，可在「设置 → 接口」切回原接口）。

版本：CODE 49 / NAME 1.0.48；`app/build.gradle` versionCode 49 / versionName 1.0.48；workflow tag `moliys-1.0.48`；site.pak 重打（78 文件，SHA256 `14e51431f9aa56d6a4812d2cd221ba8a7d261472e5488f74bc4705c4c740d667`）。

交付记录（1.0.48）：

- 代码提交：`e2aa198f344bee7a19cb6083ca4e2524409120dc`（11 文件）
- CI：run `35517227303`（head_sha `e2aa198f`）**success**
- 产物：package `com.fongmi.android.tvceshi`，versionCode `49`，versionName `1.0.48`，appname `过包名版本测试版`
- APK SHA256：`077b91f4ea5c6b30be407fe180e90eefa210838b5c655d4e67b5a197fa1ce994`
- 上传：`https://tvbox.moliys.icu/apk/tvbox-moliys-bypass-test-1.0.48.apk`（141383823 字节，HTTP 206/200）
- 站点校验：3 段内联 JS 全部通过 `node --check`；重打后 pak 内可见 `SITE_VERSION = '3.0.30'`、`data-api` 按钮模板、`openCaiSite` 调用

真机验证要点：

1. 采集页点任一「打开站点」→ 授权通过后应加载该采集接口的配置并进入内置影视（不再出现网页）。
2. 影视页应能浏览该采集接口的列表/详情，点集数能起播（苹果CMS 直连 m3u8）。
3. 点击后可返回「设置 → 接口」切回原接口（采集接口会作为一条配置保留）。

## 14. 接口页每行加「打开」按钮（1.0.49）

需求：接口页所有接口（含原始线路/主线路/备份线路每一行）也加「打开」按钮，走与采集「打开站点」相同的隐藏+授权门禁流程。

站点侧（`site-src/index.html`）：

- `renderApi()` 的 `lineRow()` 在「查看站源」后新增 `<button class="open-api-btn" data-url data-name>打开</button>`，因此每条线路都有。
- 新增 `.open-api-btn` 点击委托，调 `TVBoxNative.openInterface(name, url)`；非 App 环境仅 `alert` 提示。
- CSS：`.open-api-btn` 复用 `.decrypt-btn/.viewsrc-btn` 线路按钮样式；新增 `body.no-license .open-api-btn { display: none !important; }`。
- `applyLicenseUI()` 增加 `document.body.classList.toggle('no-license', !ok)`，未授权时该按钮随 `settingsBtn` 一起隐藏（重新渲染也不会漏）。
- `SITE_VERSION` 3.0.30→3.0.31；`config.json` 的 `site.version` 3.0.30→3.0.31。

原生侧：

- `MainActivity` 删除原 ACTION_VIEW 版 `openVideoInterface(url)`，改为 `openInterfaceConfig(name, url)`：`Config.find(url, name, 0)` + `VodConfig.load(cfg, cb)`，成功后 `openVideoHome()`（即 `hide_nav=true`，与「影视主页」「采集打开站点」一致）。
- Bridge `openInterface(String url)` 改为 `openInterface(String name, String url)`，未授权 Toast + 弹授权框（同 `openCaiSite`）。

已知行为：与采集「打开站点」相同，打开后该接口成为当前激活接口（写入配置表，可在「设置 → 接口」切回）。

版本：CODE 50 / NAME 1.0.49；`app/build.gradle` versionCode 50 / versionName 1.0.49；workflow tag `moliys-1.0.49`；site.pak 重打（78 文件，SHA256 `1c01c3bb1c6ba287b413d5ff572413acc69a41044e2b8cba857f908f06a93ff4`）。

交付记录（1.0.49）：

- 代码提交：`703aba083ca8b6dd4d5763090281eafa55182442`（8 文件）
- CI：run `35520907190`（head_sha `703aba08`）**success**
- 产物：package `com.fongmi.android.tvceshi`，versionCode `50`，versionName `1.0.49`，appname `过包名版本测试版`
- APK SHA256：`832ec6f5e5c436e0805d788257ff68cf833735ce05c921c82f6ef11937cf48d3`
- 上传：`https://tvbox.moliys.icu/apk/tvbox-moliys-bypass-test-1.0.49.apk`（141384075 字节，HTTP 206/200）
- 站点校验：3 段内联 JS 全部通过 `node --check`；重打后 pak 内可见 `SITE_VERSION = '3.0.31'`、`open-api-btn` 按钮模板与委托

真机验证要点（1.0.49）：

1. 接口页每行（原始/主/备份线路）都有「打开」，点后进入内置影视且底部标签隐藏。
2. 未授权时「打开」与「设置」一起隐藏。
3. 打开后可在「设置 → 接口」切回原接口。
