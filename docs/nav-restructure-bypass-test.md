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

## 15. 直播页每行加「打开」按钮并直接进入直播播放（1.0.50）

需求：直播页每条直播源（含原始线路/主线路/备份线路每一行）也加「打开」按钮，点击后按配置方式加载该直播源，并直接进入内置直播播放界面。

站点侧（`site-src/index.html`）：

- `renderLiveList()` 的 `lineRow()` 在「复制」后新增 `<button class="open-api-btn" data-kind="live" data-url data-name>打开</button>`，因此每条直播线路都有。
- 复用接口页的 `.open-api-btn` 点击委托，按 `data-kind` 分流：`live` 调 `TVBoxNative.openLiveSource(name, url)`，其余调 `TVBoxNative.openInterface(name, url)`；非 App 环境仅 `alert` 提示。
- 复用既有样式与 `body.no-license .open-api-btn { display: none !important; }`，未授权时直播「打开」与「设置」一起隐藏，无需新增 CSS。
- `SITE_VERSION` 3.0.31→3.0.32；`config.json` 的 `site.version` 3.0.31→3.0.32。

原生侧（`MainActivity`）：

- 新增 `openLiveSource(name, url)`：`Config.find(url, name, 1)`（type 1 = 直播）+ `LiveConfig.load(cfg, cb)`，成功后 `openVideoLive()` 直接进入 `LiveActivity`，失败 Toast「直播源加载失败，请检查地址」。
- 新增 Bridge `openLiveSource(String name, String url)`，未授权 Toast + 弹授权框（与 `openCaiSite`/`openInterface` 一致）。
- `LiveConfig` 支持单仓 JSON、多仓 `urls`、以及纯文本电视源（`LiveParser.text`），三类直播源都能直接加载。

已知行为：打开后该直播源成为当前激活直播源（写入配置表），可在「设置 → 直播」切回。

版本：CODE 51 / NAME 1.0.50；`app/build.gradle` versionCode 51 / versionName 1.0.50；workflow tag `moliys-1.0.50`；site.pak 重打（78 文件，SHA256 `5da7918c7cbdf81c2dde3ae859bb79973ee3ca9a36fd5e75c8df98ab0d6a3a4b`）。

交付记录（1.0.50）：

- 代码提交：`6723f9fce28f89a0486c2cea30a12a5067bfd193`（7 文件）
- CI：run `35522489175`（head_sha `6723f9fc`）**success**
- 产物：package `com.fongmi.android.tvceshi`，versionCode `51`，versionName `1.0.50`，appname `过包名版本测试版`
- APK SHA256：`415155764213db22d6d16e75d3c14c5c215f02edfa1c792de2f0e858cd832c9b`
- 上传：`https://tvbox.moliys.icu/apk/tvbox-moliys-bypass-test-1.0.50.apk`（141384155 字节，HTTP 206/200）
- 站点校验：3 段内联 JS 全部通过 `node --check`；重打后 pak 内可见 `SITE_VERSION = '3.0.32'`、直播行 `data-kind="live"` 按钮模板

真机验证要点（1.0.50）：

1. 直播页每行（原始/主/备份线路）都有「打开」，点后直接进入内置直播播放界面。
2. 未授权时直播「打开」与「设置」一起隐藏。
3. 打开后可在「设置 → 直播」切回原直播源。

## 16. 首页导航作为启动主界面（1.0.51）

需求：把导航卡片页（原 `导航3(3).html` 设计稿）按实际情况改造成 App 启动后的主界面，卡片点击后跳转到各对应界面。

实现方式：不新增页面、不改启动 URL。站点启动页 `index.html` 新增 `home` 面板并设为默认激活标签，导航卡片即主界面；卡片统一走已有的 `showPanel()` / 原生桥，不需要新增路由。

站点侧（`site-src/index.html`）：

- `tabsData` 首位新增 `{ id: "home", label: "首页", hideSearch: true }`；`currentTabId` 默认 `'api'` 改为 `'home'`；`api-panel` 去掉 `active`，新增 `home-panel` 并为 `active`。
- 新增 `goTab(id)`：设置 `currentTabId` → `renderTabs()` → `showPanel(id)`；卡片点击统一调它。
- `showPanel()` 首行增加 `document.body.classList.toggle('home-mode', tabId === 'home')`；`body.home-mode` 下隐藏页面原有的 `header-block` 与页面级搜索框，避免与首页自带头部重复。
- 首页头部：问候语（按小时）+ 标题 + 版本角标（取 `SITE_VERSION`）+ 时钟 + 日期，`updateHomeClock()` 每 10s 刷新。
- 首页自带搜索框 `#homeSearch` + `filterHomeCards()`，按卡片标题/副标题过滤，并自动隐藏空分类。
- 卡片样式用站点主题变量（`--card/--border-soft/--text/--text2/--shadow`），深色主题跟随；窄屏（<=480px）两列。
- `SITE_VERSION` 3.0.32→3.0.33；`config.json` 的 `site.version` 同步；`html.native-app` 下 `首页` 标签加 🏠 图标。

卡片 → 目标界面映射（18 张，与 18 个标签一一对应）：

| 分类 | 卡片 | data-go | 落点 |
| --- | --- | --- | --- |
| 影视观看 | 在线影视 | online | 原生影视首页（授权门禁） |
| 影视观看 | 在线直播 | liveapp | 原生直播（授权门禁） |
| 影视观看 | 直播聚合 / 短剧 / FM电台 | live / duanju / fm365 | 对应面板 |
| 接口资源 | 点播 / 采集 | api / cai | 对应面板 |
| 接口资源 | 弹幕 / EPG接口 | danmu / epg | 对应面板 |
| 接口资源 | 音源 / 音乐 / 书源 / 漫画源 / 图床 | yinyuan / music / shuyuan / comic / imgbed | 对应面板 |
| 工具 | 解密工具 / 直播源转换 / 下载 | parse / convert / download | 对应面板 |
| 其他 | 关于 | about | 对应面板 |

- 授权联动：`online`/`liveapp` 两张卡片带 `lic-only`，`body.no-license` 下隐藏（与「设置」按钮、「打开」按钮同一套状态）。
- 简洁模式联动：`parse`/`convert`/`live`/`epg`/`download`/`danmu`/`about` 在简洁模式本就被 CSS 隐藏，对应卡片带 `full-only`，`body.simple-mode` 下隐藏，避免点到空面板。App 内 `currentMode` 被强制为 `full`，所以 App 内 18 张卡片全部可见。

验证（`jsdom` 加载重打后的 site.pak 页面）：

- 启动即 `home-panel` 激活、`body` 带 `home-mode`；时钟/日期/问候/版本角标（v3.0.33）正常；卡片 18 张；标签栏含「首页」。
- 点「点播」卡片 → `api-panel` 激活、`home-mode` 移除、标签栏「点播」高亮。
- 点标签栏「首页」→ 回到首页并恢复 `home-mode`。
- 搜索「直播」→ 命中 在线直播/直播聚合/直播源转换，分类自动收敛为 2 个。

版本：CODE 52 / NAME 1.0.51；`app/build.gradle` versionCode 52 / versionName 1.0.51；workflow tag `moliys-1.0.51`；site.pak 重打（78 文件）。

交付记录（1.0.51）：

- 代码提交：`d089dae4e8f7a41221c9a6a7208e993d3b64b846`（6 文件）
- CI：run `35525366004`（head_sha `d089dae4`）**success**
- 产物：package `com.fongmi.android.tvceshi`，versionCode `52`，versionName `1.0.51`，appname `过包名版本测试版`
- APK SHA256：`be24906a03ba043d59cc5d477cec17b6ed4c1c13c5fb7a58dcd0593ecf754b81`
- 上传：`https://tvbox.moliys.icu/apk/tvbox-moliys-bypass-test-1.0.51.apk`（141386411 字节，HTTP 206/200）

真机验证要点（1.0.51）：

1. 启动 App 直接看到导航主界面（问候/时钟/版本/搜索框 + 分组卡片），页面顶部原大标题与搜索框在首页隐藏。
2. 点「在线影视」「在线直播」进原生界面；未授权时两张卡片与「设置」一起隐藏。
3. 点「点播」「直播」「采集」等卡片切到对应面板；点标签栏「首页」可回到主界面。
4. 首页搜索框输入关键字可过滤卡片并自动隐藏空分组。

## 17. 首页照设计稿、隐藏底部标签、主页 6 次设备码（1.0.52）

反馈与改动：

1. **样式跑偏** → 上一版把首页做成了蓝色渐变 hero 卡片，与设计稿不符。改回设计稿布局：删除 `.home-hero` 渐变块，改为「彩虹装饰条 + 居中头部（问候语 / 标题+版本角标 / 大号时钟 / 日期）」直接铺在页面背景上；分组标题「圆点 + 名称 + N 个」，卡片 `minmax(170px,1fr)`、白底细边框、40px 圆角图标块；窄屏 768px/480px 两档收敛。颜色仍用站点主题变量，深色主题跟随。
2. **不要底部标签** → App 内嵌版隐藏站点自己的标签栏（`html.native-app .nav-tabs { display: none !important; }`，原样式把 `.nav-tabs` 固定到屏幕底部当标签栏），导航全部收敛到首页卡片。
3. **返回首页** → 标签栏没了，新增悬浮 `#homeBackBtn`（仅 App 内显示，`html.native-app #homeBackBtn.show`），非首页面板自动出现，点击 `goTab('home')` 回首页；`showPanel()` 内同步显隐。
4. **主页 6 次设备码** → 原注入逻辑只认 `data-tab="about"`（底部标签栏的「关于」按钮，现已隐藏）。首页头部三个元素（问候语、版本角标、时钟）加 `data-lic-tap="1"`，`injectVideoEntry()` 的注入脚本改为 `data-tab==='about' || data-lic-tap==='1'`，4 秒内连点 6 次即弹授权面板看设备码；「关于」标签路径保留兼容。

站点校验（jsdom 加载重打后的 pak）：首页默认激活 + `home-mode`；时钟/日期正常、角标 v3.0.34；卡片 18 张；`data-lic-tap` 目标 3 个；返回按钮在首页隐藏、切到点播面板后为 `show`、点击回到首页。3 段内联 JS 通过 `node --check`。

版本：CODE 53 / NAME 1.0.52；`app/build.gradle` versionCode 53 / versionName 1.0.52；workflow tag `moliys-1.0.52`；站点 `SITE_VERSION` 3.0.34；site.pak 重打（78 文件）。

交付记录（1.0.52）：

- 代码提交：`aaf064bdb65aeb8ccb7cacb92d1c5265c9b5369e`（7 文件）
- CI：run `35526651203`（head_sha `aaf064bd`）**success**
- 产物：package `com.fongmi.android.tvceshi`，versionCode `53`，versionName `1.0.52`，appname `过包名版本测试版`
- APK SHA256：`32a3bc1ac477e857baf450ce617fb3cc0784087da6362211bd359e5fd23097d7`
- 上传：`https://tvbox.moliys.icu/apk/tvbox-moliys-bypass-test-1.0.52.apk`（141386831 字节，HTTP 206/200）

真机验证要点（1.0.52）：

1. 启动即首页：顶部彩虹条 + 居中问候/标题/时钟/日期，无蓝色渐变卡片；底部不再有标签栏。
2. 点任意卡片进入对应面板，右下角出现「返回首页」，点击回首页。
3. 首页头部（版本角标 / 问候 / 时钟）任意一处 4 秒内连点 6 次，弹出授权面板显示设备码；点「复制设备码」可复制。

## 18. 界面回退到 1.0.50（1.0.53）

反馈：首页导航改版后的观感不接受，要求回退到 1.0.50 的界面。

处理：把站点与原生相关文件整文件恢复到 1.0.50 的提交 `6723f9fc`：

- `git checkout 6723f9fc -- site-src/index.html site-src/config.json app/src/main/assets/site.pak app/src/main/java/com/moliys/tvbox/MainActivity.java app/src/main/java/com/moliys/tvbox/Version.java app/build.gradle .github/workflows/build.yml`
- 结果：`home-panel` 与首页卡片整块消失，启动仍是站点原有「大标题 + 搜索框 + 顶部标签栏」，App 内标签栏继续固定在底部；`injectVideoEntry()` 恢复为只认 `data-tab="about"` 连点 6 次；1.0.51/1.0.52 的首页导航、彩虹条、返回首页悬浮按钮、`data-lic-tap` 全部移除。
- 保留：1.0.48 采集「打开站点」、1.0.49 接口页「打开」、1.0.50 直播页「打开」不受影响（均在更早提交）。
- 版本号前移，不复用旧号：`SITE_VERSION` 3.0.35；`Version` CODE 54 / NAME 1.0.53；`app/build.gradle` versionCode 54 / versionName 1.0.53；workflow tag `moliys-1.0.53`；site.pak 重打（78 文件，大小与 1.0.50 一致 2076972 字节）。

交付记录（1.0.53）：

- 代码提交：`a8f9ec328510ff2e183c9149a66e4ceef1f9c058`（7 文件，`+13 / -304`）
- CI：run `35527571593`（head_sha `a8f9ec32`）**success**
- 产物：package `com.fongmi.android.tvceshi`，versionCode `54`，versionName `1.0.53`，appname `过包名版本测试版`
- APK SHA256：`2010d91a1d58e00c6722a671cbac44b0693851fda87dab060f45e03caf670ca7`
- 上传：`https://tvbox.moliys.icu/apk/tvbox-moliys-bypass-test-1.0.53.apk`（141384159 字节，HTTP 206/200）
- 1.0.50 安装包保留可下载：`https://tvbox.moliys.icu/apk/tvbox-moliys-bypass-test-1.0.50.apk`

回退锚点：1.0.51/1.0.52 的实现在 `d089dae4`（首页导航主界面）与 `aaf064bd`（照设计稿 + 隐藏底部标签 + 返回首页 + 主页 6 次设备码），如需重新取回可按这两个提交 cherry-pick。

## 19. 弹幕接口「应用」按钮 + 后台配置弹幕格式（1.0.54）

需求：

1. 弹幕页每条接口加「应用」按钮，一键把弹幕接口写进 App（不再只靠复制粘贴）。
2. 弹幕格式由后台配置，不写死在页面里；弹幕链接形如 `{源地址}/api/v2/fongmi/danmaku?name={name}&episode={episode}`。

站点侧（`site-src/index.html`、`site-src/config.json`）：

- 后台配置：`config.json` 新增 `danmu.api = "/api/v2/fongmi/danmaku?name={name}&episode={episode}"`；`danmu.json` 单条可用 `"api"` 覆盖（支持绝对地址或相对路径），页面只负责拼接。
- 新增 `danmuApiTemplate()` / `danmuApiUrl(source, tpl)`：`SITE_CFG.danmu.api` 为默认模板，条目自带 `api` 优先。
- `renderDanmu()`：行内展示完整弹幕链接（源地址 + 模板），新增 `.danmu-apply-btn`「应用」按钮（保留「复制」），面板标题补一行「弹幕格式：`{源地址}<模板>`」方便核对；`Promise.all([ensureConfig(), loadData('danmu')])` 保证后台配置先就绪。
- 新增 `applyDanmu(name, api)`：有原生桥调 `TVBoxNative.applyDanmaku(name, api)`，纯网页环境 `alert('请在沫离 App 内使用该功能')`。
- 授权门禁与「打开」一致：`body.no-license .danmu-apply-btn { display: none !important; }`。
- 版本：`SITE_VERSION` 3.0.35 → 3.0.36，`config.json` `site.version` 同步。

原生侧（`MainActivity.java`）：

- Bridge 新增 `applyDanmaku(name, url)`：与 影视主页/接口打开 同一套授权门禁，未授权 Toast + 授权面板。
- 新增私有 `applyDanmakuConfig(name, url)`：`DanmakuSetting.isValidApiUrl()` 校验后写入 `putApiUrl()`，并打开 `putLoad(true)` + `putAuto(true)`（`DanmakuApi.canSearch()` 需要三者同时成立才会在播放时自动搜弹幕），Toast「已应用：<源名>」。
- 播放器侧无需改动：`DanmakuApi.newCall()` 原生支持含 `{name}`/`{episode}` 占位符的模板地址，直接替换后请求。

打包与校验：

- `tools/repack_site.py --site-dir site-src --out app/src/main/assets/site.pak --manifest-url https://tvbox.moliys.icu/apk/version-moliys.js --no-fetch` → 78 文件 2077452 字节，SHA256 `0498bba391a66beca627a0f2634104bca94d857b1f1cb81eade586874f07d034`。
- 站点内联 JS 3 段 `node --check` 通过；jsdom 载入重打后 `site.pak` 页面：弹幕页渲染 12 条、12 个「应用」按钮，`data-url` 为完整模板地址，点击在无原生桥时 alert、注入 `TVBoxNative.applyDanmaku` 后回调 `("ecs源", "http://ecs.dysobo.cn:9321/87654321/api/v2/fongmi/danmaku?name={name}&episode={episode}")`；`body.no-license` 下按钮 `display:none`。
- 版本：`Version` CODE 55 / NAME 1.0.54；`app/build.gradle` versionCode 55 / versionName 1.0.54；workflow tag `moliys-1.0.54`。

验收清单（真机）：

- [ ] 弹幕页每条都有「应用」「复制」，行内显示完整弹幕链接
- [ ] 已授权点「应用」→ Toast「已应用：<源名>」，播放影片时自动拉取该源弹幕
- [ ] 未授权：弹幕页不显示「应用」按钮；若已进入则点后在原生侧被拦截并弹授权面板
- [ ] 纯网页打开弹幕页点「应用」→ 提示「请在沫离 App 内使用该功能」
- [ ] 弹幕格式改动只需改后台 `config.json` 的 `danmu.api`，页面无需重发

交付记录（1.0.54）：

- 代码提交：`ecaef7269cfc745969b79a2102304b2e142692e9`（7 文件，`+84 / -16`）
- CI：run `35528944706`（head_sha `ecaef726`）**success**
- 产物：package `com.fongmi.android.tvceshi`，versionCode `55`，versionName `1.0.54`
- APK SHA256：`6668c71bce597e00a0b14cb1e393a57bf5d84aabb4fe10b96871754c5e754d04`
- 上传：`https://tvbox.moliys.icu/apk/tvbox-moliys-bypass-test-1.0.54.apk`（141384643 字节，HTTP 206/200）

## 20. 弹幕列表改回显示源地址，应用时才拼接（1.0.55）

反馈：1.0.54 把行内展示与「复制」的内容也换成了完整模板地址，长链接换行难看，也让「复制给其它壳子」变麻烦。要求**弹幕源地址保持原样**（方便别人复制），`/api/v2/fongmi/danmaku?name={name}&episode={episode}` 只在点「应用」时拼接。

处理（仅站点侧 + 版本号，原生与后台配置不变）：

- `renderDanmu()`：行内展示与「复制」按钮回到 `s.url`（源地址）；「应用」按钮仍带完整地址 `源地址 + danmu.api`。
- 面板标题：改为「列表显示弹幕源地址，「复制」复制源地址给其它壳子，「应用」一键写入沫离 App」，并把格式行改为「应用时自动拼接：`{源地址}<模板>`」。
- 拼接逻辑与后台配置沿用第 19 节：`danmuApiTemplate()` / `danmuApiUrl(source, tpl)`，模板仍来自 `config.json` 的 `danmu.api`，条目 `api` 可覆盖。
- 版本：`SITE_VERSION` 3.0.36 → 3.0.37，`config.json` `site.version` 同步；`Version` CODE 56 / NAME 1.0.55；`app/build.gradle` versionCode 56 / versionName 1.0.55；workflow tag `moliys-1.0.55`；site.pak 重打（78 文件 2077468 字节，SHA256 `537b7b006eadc92d86131709cf3bbd7111c822988d536de75ba47c04e4acd82c`）。

校验：3 段内联 JS `node --check` 通过；jsdom 载入重打后 `site.pak` 弹幕页 —— 行内显示 `http://ecs.dysobo.cn:9321/87654321`、`复制` 同值、`应用` 的 `data-url` 与原生回调为 `http://ecs.dysobo.cn:9321/87654321/api/v2/fongmi/danmaku?name={name}&episode={episode}`。

交付记录（1.0.55）：

- 代码提交：`65ccd181ecd335c8155ce24c15577301333fdc7b`（6 文件，`+11 / -11`）
- CI：run `35530162528`（head_sha `65ccd181`）**success**
- 产物：package `com.fongmi.android.tvceshi`，versionCode `56`，versionName `1.0.55`
- APK SHA256：`339236666d0b2d8264260ac6936a46b34aad1d72661b0ecbc1c883698ee464da`
- 上传：`https://tvbox.moliys.icu/apk/tvbox-moliys-bypass-test-1.0.55.apk`（141384655 字节，HTTP 206/200）

## 21. 头部顶栏整理：主题按钮 / Logo / 设置按钮同行同尺寸（1.0.56）

反馈：「切换白天/黑色」按钮与 Logo（头像）大小不一样、位置不齐，要求整体整理。

原因：三个按钮原本是 `.container` 下的绝对定位元素（`top:14px` / `left:10px` / `right:10px`，36px），而 `h1` 是普通块级、带浏览器默认 `margin: 0.67em 0`（约 14.5px），Logo 用 `vertical-align: -.5rem` 硬调，所以按钮中心在 32px、Logo 中心在 ~54px，`36px` vs `32px` 尺寸也不一致。

处理（`site-src/index.html`，纯 CSS + DOM 层级）：

- 新增 `.header-top`：`position: relative; display: flex; align-items: center; justify-content: center; min-height: 38px`，把 `#themeToggleBtn`、`#modeToggleBtn`、`#settingsBtn` 与 `h1` 收进同一行（三个按钮改为 `.header-top` 内的绝对定位，`top: 50% + translateY(-50%)` 垂直居中，左右各贴边）。
- 统一尺寸：按钮 `38×38`（圆/胶囊，含移动端 `34px`），`h1 .header-logo` 同步 `38×38`（移动端 `34px`）。
- `h1` 改为 `display:flex; align-items:center; justify-content:center; gap:8px; margin:0`，Logo 去掉 `vertical-align` / `margin-right` 微调，由 flex 保证与标题、按钮严格对齐；标题仍由 `justify-content:center` 居中（绝对定位的按钮不参与布局，标题居中不受左右按钮宽度差影响）。
- `.header-block` 内边距 `24px 16px 20px` → `18px 16px 18px`（移动端 `14px 12px 14px`），高度更紧凑。
- 顺带修掉浅色主题下的遗漏：`body[data-theme="light"]` 颜色规则补上 `#settingsBtn`（原先只有主题/模式按钮，设置按钮在浅色下是白字浅底）。
- 版本：`SITE_VERSION` 3.0.37 → 3.0.38，`config.json` `site.version` 同步；`Version` CODE 57 / NAME 1.0.56；`app/build.gradle` versionCode 57 / versionName 1.0.56；workflow tag `moliys-1.0.56`；site.pak 重打（78 文件 2077452 字节，SHA256 `35333e971fd07ae14c2c7c954db838a40a2359c1cc23dbfe06ea226c8b4da4ec`）。

校验：3 段内联 JS `node --check` 通过；jsdom 载入重打后 `site.pak`：`.header-top` 子节点顺序为 `[themeToggleBtn, modeToggleBtn, settingsBtn, h1]`，`h1` 计算样式 `display:flex` / `margin:0`，Logo `38×38`，`#themeToggleBtn` `38×38 absolute top:50% translateY(-50%) left:0`，`#modeToggleBtn`/`#settingsBtn` 高 `38` `absolute top:50% right:0`，三个按钮 id 均可用、无 JS 报错。

交付记录（1.0.56）：

- 代码提交：`e1129b8685fa98ef086ad63588be3853c699b52b`（6 文件，`+53 / -38`）
- CI：run `35531372306`（head_sha `e1129b86`）**success**
- 产物：package `com.fongmi.android.tvceshi`，versionCode `57`，versionName `1.0.56`
- APK SHA256：`fbc261ff9e57bef67ca74c90c872c9b3cae74991dab016be07ae853aaf325d0e`
- 上传：`https://tvbox.moliys.icu/apk/tvbox-moliys-bypass-test-1.0.56.apk`（141384639 字节，HTTP 206/200）
- 待办：真机确认 主题按钮 / Logo / 设置 三者大小一致、同一水平线；浅色主题下设置按钮可见

## 22. 点播/直播线路行：链接移到标签旁，按钮另起一行（1.0.57）

反馈：要求把点播与直播里「按钮」和「链接」的位置换一下 —— **原始线路标签旁边改为显示线路链接，原来放链接的地方改为放按钮**。

原因：点播行是 `[状态点][标签][复制/解密/查看站源/打开]` + 链接独占第二行；直播行是 `[状态点][标签][链接][复制][打开]`，链接被标签和按钮夹在中间只剩很窄一列，长链接换行成 4~6 行。

处理（`site-src/index.html`）：

- 点播 `lineRow()`：`.url-line-top` 内改为 `状态点 + 标签 + 链接`，四个按钮移入新增的 `.url-line-actions` 作为第二行。
- 直播 `lineRow()`：`.live-url-body` 内改为 `状态点 + 标签 + 链接`，`复制/打开` 移入新增的 `.live-url-actions` 作为第二行。
- CSS：`.url-line-top .url-text` / `.live-url-body .live-url-text` 改为 `flex:1; min-width:0; margin:0`（占满标签右侧剩余宽度）；`.url-line-top .url-text` 不再独占整行（删除 `display:block; flex:none; margin-top:5px`）；新增 `.url-line-actions`（`margin-top:7px`）与 `.live-url-actions`（沿用 `.live-url-line` 的 6px 间距），按钮保持右对齐（`.copy-btn` 原有 `margin-left:auto`），与改动前按钮的观感一致。
- 链接改成与标签同行后，直播长链接从原来的 4~6 行降到 1~2 行；点播链接移到标签右侧。
- 版本：`SITE_VERSION` 3.0.38 → 3.0.39，`config.json` `site.version` 同步；`Version` CODE 58 / NAME 1.0.57；`app/build.gradle` versionCode 58 / versionName 1.0.57；workflow tag `moliys-1.0.57`；site.pak 重打（78 文件 2077580 字节，SHA256 `f0dcd939b46d51380ab304f9372097b1a5332f543a8191235849dffb595139e8`）。

校验：3 段内联 JS `node --check` 通过；jsdom 载入重打后 `site.pak`：
- 点播 `.url-line` 子节点 = `[.url-line-top(hstatus, url-tag, url-text), .url-line-actions(copy-btn, decrypt-btn, viewsrc-btn, open-api-btn)]`
- 直播 `.live-url-line` 子节点 = `[.live-url-body(hstatus, live-url-tag, live-url-text), .live-url-actions(copy-btn, open-api-btn)]`
- 链接文本正常渲染，无 JS 报错。

交付记录（1.0.57）：

- 代码提交：`e6dc1cc69cc6a1bf3f7df50d5b28dbc722bc1a9a`（6 文件，`+36 / -18`）
- CI：run `35532658586`（head_sha `e6dc1cc6`）**success**
- 产物：package `com.fongmi.android.tvceshi`，versionCode `58`，versionName `1.0.57`
- APK SHA256：`7b00c4938f0c96f615ad55752c03196fe9258a4c37b0ba8b80d68b3ca5da693a`
- 上传：`https://tvbox.moliys.icu/apk/tvbox-moliys-bypass-test-1.0.57.apk`（141384767 字节，HTTP 206/200）
- 待办：真机确认 点播/直播每行都是「状态点 + 标签 + 链接」在上、「复制/解密/查看站源/打开」在下，链接不再挤成多行

## 23. 全 app 统一白天/黑夜主题（1.0.58）

反馈：整个 app 要统一颜色背景 —— 黑夜模式就是黑夜的背景，白天就是白天的背景，包括内置的影视主页、直播背景、设置背景，以及短剧、电台、图床。

### 排查结论

- 原生侧：手机版 `Theme.Base` 已经是 `Theme.Material3.DynamicColors.DayNight.NoActionBar`（`app/src/mobile/res/values/styles.xml`），但 App 从不调用 `AppCompatDelegate.setDefaultNightMode`，只能跟随系统；且桥 `setTheme(boolean light)` 过去只调 `applyBarIcons()` 改状态栏图标明暗，**不动任何背景**。
- 主站侧：`#themeToggleBtn` 切 `body[data-theme]`，但大量生成内容（弹幕/接口/关于面板、线路行）写死浅色（`#fff` / `#f8fafc` / `#e0e6ed` / `#0b1e33` / `#5a6f88` 等），黑夜模式下仍是亮的；`.embed-wrap` 也写死 `background:#f0f7ff`。
- 短剧 `duanju.moliys.icu`、图床 `img.moliys.icu` 是自建站，可改；电台 `happy.alang.run`、`fm365.space` 是第三方，只能靠外层容器背景。
- 原生硬编码暗色仅 57 处（多为遮罩/渐变），其余走 Material3 主题属性，整体转亮色可行。

### 方案（用户确认：站点开关为准，初始跟随系统）

1. **原生**
   - 新增 `app/src/main/java/com/moliys/tvbox/MoliysTheme.java`：偏好 `moliys_shell/theme`（`light`/`dark`，未存则空）；`isLight()`（未存跟随系统 `uiMode`）、`set(light)`（存值 + `AppCompatDelegate.setDefaultNightMode`）、`apply()`（启动时按存值应用）、`shellBackground()`（亮 `#F1F5FA` / 暗 `#0F1115`）、`toJson()`。
   - `App.onCreate()` 在 `Setting.applyLanguage()` 后调用 `MoliysTheme.apply()`，影视主页 `HomeActivity`、`LiveActivity`、设置（`HomeActivity` nav_position=1）随之整体切主题。
   - `MainActivity`：壳背景 / `WebView` 背景改用 `MoliysTheme.shellBackground()`，新增 `applyShellTheme()`（背景 + `applyBarIcons`）并在 `onCreate` 与桥 `setTheme` 中调用；桥新增 `getTheme()` 返回 `{"light":..,"saved":..,"mode":..}`。
2. **主站 `site-src/index.html`**
   - 主题优先级：`localStorage.site_theme` > 原生 `TVBoxNative.getTheme().saved` > `prefers-color-scheme` > `dark`（新增 `initialTheme()`）；`applyTheme()` 同时更新 `localStorage`、图标、`data-theme`，经由既有 `syncTheme()` 回写原生（原生存值即来源）。
   - 颜色统一走变量：`background:#fff`→`var(--card)`、`#f8fafc`→`var(--panel)`、`#e0e6ed`/`#eef2f7`→`var(--border-soft)`、`#0b1e33`→`var(--text-strong)`、`#5a6f88`/`#8b9eb0`/`#8fa3b8`→`var(--text2)`、`#2563eb`→`var(--accent-2)`、`#eef3fa`→`var(--tagbg)`、`#cbd5e1`→`var(--border)`、`.dl-icon`/`.embed-wrap`→`var(--card)`/`var(--panel)`，共 70+ 处。
   - 内嵌页主题联动：新增 `embedUrl(url,theme)`，对自建站（`duanju.moliys.icu`、`img.moliys.icu`）追加 `?theme=light|dark`；懒加载处与 `applyTheme` 均走它，切主题时只更新**未激活**面板以免打断使用。
3. **图床 `img.moliys.icu`（`/workspace/img-host/`）**：新增 `--solid/--soft/--soft2/--soft3/--chip/--glow/--ink2..4/--drop-*` 等变量并铺开写死色；追加 `html[data-theme="dark"]` 变量覆盖块；`index.html` 头部加引导脚本（`?theme=` > `localStorage.imghub_theme` > 系统），同步 `meta theme-color`。
4. **短剧 `duanju.moliys.icu`**：新增 `static/theme.js`（同优先级策略，键 `duanju_theme`），`index.php`/`play.php`/`search.php` 头部在样式表前引入并给 `style.css` 加 `?v=2`；`static/style.css` 追加 `html[data-theme="dark"]` 变量块与 `.tag-*`/`.alert-*` 暗色覆盖，`background:white`→`var(--card)`、`#f8fafc`→`var(--soft)`；各页内联样式里的 `#f8fafc`/`#e2e8f0`/`#f1f5f9`/`#d1fae5`/`#059669`/`#f0f4ff` 改为变量（新增 `--soft`/`--badge-ok-bg`/`--badge-ok-ink`）。

### 校验

- 主站：3 段内联 JS `node --check` 通过；jsdom 载入重打后 `site.pak`，5 种场景 `data-theme` 全部符合预期：无原生+系统白天→`light`、无原生+系统黑夜→`dark`、原生已存 light→`light`、原生已存 dark→`dark`、原生未存+系统白天→`light`；`embedUrl('https://duanju.moliys.icu/','dark')` = `https://duanju.moliys.icu/?theme=dark`，`embedUrl('https://img.moliys.icu/','light')` = `?theme=light`，第三方 `fm365.space` 不变。
- 图床：jsdom 验证 `?theme=dark` → `data-theme=dark`；系统为暗但 `?theme=light` 时仍为 `light`（参数优先）。
- 短剧：`php -l` 三个页面均无语法错误；jsdom（`resources:'usable'` 加载外部 `theme.js`）验证 `?theme=dark` → `data-theme=dark`，`localStorage.duanju_theme=dark`，样式表引用为 `static/style.css?v=2`。
- 原生：本地无 Android 工具链，由 CI 编译验证通过。

### 交付记录（1.0.58）

- 代码提交：`e81c5199b7431fe6ebe64b360b9ce1bdb8c5e9b1`（9 文件，`+232 / -99`，新增 `MoliysTheme.java`）
- CI：run `35534967118`（head_sha `e81c5199`）**success**
- 产物：package `com.fongmi.android.tvceshi`，versionCode `59`，versionName `1.0.58`
- APK SHA256：`6f486751d786242c7cb28d13041897e616060c1bdf9799c259dd767119b42ad6`
- 上传：`https://tvbox.moliys.icu/apk/tvbox-moliys-bypass-test-1.0.58.apk`（141385155 字节，HTTP 206/200）
- site.pak：78 文件 2077964 字节，SHA256 `4040989eb3a6ea37687c0f4a82cfdf80325d52568bea2b0b4b3d6cf153848140`
- 站点版本：`SITE_VERSION` 3.0.40，`config.json` `site.version` 3.0.40
- 服务器文件：`/www/wwwroot/img.moliys.icu/{index.html,assets/style.css}`、`/www/wwwroot/duanju.moliys.icu/{static/theme.js,static/style.css,index.php,play.php,search.php}`
- 待办：真机确认 切白天→站点、影视主页、直播、设置、短剧、图床全部变亮；切黑夜全部变暗；首装跟随系统；电台（第三方 iframe）只有外层容器随主题

## 24. 原生影视主页 / 直播 / 设置跟随站点白天黑夜主题（1.0.59）

反馈：白天模式下打开「影视主页 / 直播 / 设置」三页仍是黑的（附 3 张截图：影视主页选中标签为紫色、直播为紫色渐变、设置为纯黑）。1.0.58 只切了外壳，原生三页并未真正跟随。

### 排查结论

- 主因1 配色：手机版 `Theme.Base` 继承 `Theme.Material3.DynamicColors.DayNight.NoActionBar`（`app/src/mobile/res/values/styles.xml`），Material3 动态取色按系统壁纸生成配色，原生控件（选中标签等）不取自站点配色。
- 主因2 硬编码背景：`HomeActivity.java` 写死 `setBackgroundColor(0xFF0F1115)`，白天也是深色底。
- 主因3 壁纸层：移动端所有 Activity 的 `BaseActivity.customWall()` 默认返回 `true`，会垫一层 `CustomWallView`；默认壁纸为 `WALL_DREAM_PURPLE`（梦幻紫霞），所以直播页呈紫色渐变。
- 旁因：`WebHomeChromeController.useDarkIcons()` 用系统 `uiMode` 判断，绕过 `MoliysTheme`。

### 方案（用户确认：默认改为「跟随主题」）

1. `Theme.Base` 去掉 `DynamicColors`，改为 `Theme.Material3.DayNight.NoActionBar`，并用一整套 `moliys_*` 颜色覆盖 `colorPrimary`/`colorPrimaryContainer`/`colorSurface`/`colorSurfaceVariant`/`colorOnSurface`/`colorOnSurfaceVariant`/`colorOutline`/`colorSecondaryContainer` 等，白天/黑夜各一套（`app/src/main/res/values/moliys_theme.xml`、`app/src/main/res/values-night/moliys_theme.xml`）。
2. 壳背景色 `moliys_bg` 单独放在 `moliys_shell.xml`：白天 `#FFF1F5FA`、黑夜 `#0F1115`，与 `MoliysTheme.SHELL_LIGHT/SHELL_DARK` 一致；`Theme.MoliysShell` 的 `windowLightStatusBar`/`windowLightNavigationBar` 同步分档。
3. `Setting.java` 新增 `WALL_FOLLOW_THEME = 0`，`getWall()` 默认值由 `WALL_DREAM_PURPLE` 改为 `WALL_FOLLOW_THEME`；`getBuiltInWallName`/`getWallDesc` 返回「跟随主题」。已手动选过壁纸的用户保持原选择。
4. `CustomWallView.java` 在「跟随主题」模式下改用 `MoliysTheme.shellBackground()` 作为底色（含缓存分支与 `getWallColor()`），不再走图片壁纸。
5. `HomeActivity.java` 去掉 `setBackgroundColor(0xFF0F1115)`，改 `MoliysTheme.shellBackground()`。
6. `BaseActivity.enableEdgeToEdge()` 按 `MoliysTheme.isLight()` 选择 `SystemBarStyle.light/dark`；`onResume()` 重新应用，保证切主题后状态栏图标正确。
7. `WebHomeChromeController.useDarkIcons()` 改走 `MoliysTheme.isLight()`。
8. `ToolbarTextAppearance`、`fragment_setting.xml` 的 `navigationIconTint` 改用 `?attr/colorOnSurface`。
9. 直播页浅色可读性：`selector_live.xml`/`selector_live_text.xml`/`shape_live.xml`/`activity_live.xml` 里写死的白与半透明白改为 `?attr/colorSurfaceVariant`/`colorPrimaryContainer`/`colorOnSurface`/`colorOnSurfaceVariant`，白天不再出现白字白底。

### 构建事故与修复

- CI run `35537612560`（`d88f168d`）**失败**：`mergeMobileArm64_v8aReleaseResources` 报 `Duplicate resources` —— 新增的 `values/moliys_theme.xml` 与既有 `values/moliys_shell.xml` 同时定义了 `color/moliys_bg`。
- 修复：`moliys_bg` 从两份 `moliys_theme.xml` 移除，只留在 `moliys_shell.xml`，并新增 `values-night/moliys_shell.xml` 提供黑夜档；`Theme.MoliysShell` 在两个 config 目录各定义一次（Android 的 style 不跨 config 合并）。
- 重跑 run `35538200495`（`dabc6187`）第 1 次仍失败，日志显示是 CI 网络问题（`Downloading gradle-9.5.1-bin.zip` → `java.net.SocketException: Connection reset by peer`），与代码无关；`rerun-failed-jobs` 第 2 次 **success**。

### 校验

- 4 个新增/修改 XML 均通过格式校验。
- 跨源码集重名扫描：同一源码集内不再有重复 `color` 资源；`moliys_*` 只出现在 `main` 源码集；day/night 覆盖完整（`moliys_icon_bg` 为不随主题变化的图标底色，故意只定义默认值）。
- 原生编译本地不可用，由 fork CI `:app:assembleMobileArm64_v8aRelease` 验证通过。

### 交付记录（1.0.59）

- 代码提交：`5866f2f2`（原生三页跟随主题，12 文件 `+85/-20`）、`d88f168d`（直播浅色可读，4 文件 `+15/-14`）、`dabc6187`（重复资源修复，4 文件 `+15/-5`）
- CI：run `35538200495`（head_sha `dabc6187`，第 2 次执行）**success**
- 产物：package `com.fongmi.android.tvceshi`，versionCode `60`，versionName `1.0.59`
- APK SHA256：`c36a7aeddd5f177eb975be7685f8e18f20e6a6231d4a434f0e9d86a80855caa2`（141384703 字节）
- 上传：`https://tvbox.moliys.icu/apk/tvbox-moliys-bypass-test-1.0.59.apk`（HTTP 206，远端 141384703 字节）
- 站点版本：未改 site-src，`SITE_VERSION` 与 `config.json` `site.version` 维持 3.0.40
- 待办：真机确认 切白天→影视主页、直播、设置全部变亮且无白字白底；切黑夜全部变暗；首装默认「跟随主题」；已选过壁纸的用户保留原壁纸

## 25. 白天模式可读性修复：影视主页工具栏/分类栏/底部导航 + 直播列表（1.0.60）

反馈（附截图）：切白天后白色文字看不到；影视主页的「搜索按钮」「最近观看」「右上角三个点」不明显。截图 3 显示直播页背景已变亮，但未选中的频道文字（`002 CCTV1`、`004 CCTV3`…）与左侧分组（`卫视频道`、`精彩频道`…）仍是白色。

### 排查结论

- **直播列表走的是 classic 资源**：`LiveSetting.isListStyleClassic()` 默认值就是 `LIST_STYLE_CLASSIC`（`LiveSetting.java:51` `Prefers.getInt("live_list_style", LIST_STYLE_CLASSIC)` 且 `LIST_STYLE_CLASSIC = 1`）。`GroupAdapter.java:114`、`ChannelAdapter.java:238/239`、`EpgDataAdapter.java:79/80` 按该开关在 `selector_live_text_classic` / `selector_live_text` 间二选一，`ChannelAdapter.java:237` 与 `GroupAdapter.java:113` 同理在 `shape_live_classic` / `shape_live` 间二选一。1.0.59 只改了**非 classic** 分支，因此直播列表文字与行底色仍是写死的白色。
- **影视主页工具栏**：`fragment_vod.xml:21` 用 `app:menu="@menu/menu_vod"`，`menu_vod.xml` 四个条目全部写死 `app:iconTint="@color/white"`（`search`、`history`、`web_home_fullscreen`、`more_actions`），正好对应反馈的搜索 / 最近观看 / 右上角三个点。
- **影视主页分类栏**：`fragment_vod.xml:65` 的 `tools:listitem="@layout/adapter_type"`，`adapter_type.xml:11` 用 `@color/selector_text`，其默认项是 `@color/white`；`adapter_collect.xml:13` 同源。分类 chip 底色 `shape_item_round` → `selector_item`（`black` 15%），白天为浅灰，白字不可见。
- **底部导航**：`activity_home.xml:18` 导航条背景 `@color/transparent`（即主题背景），`selector_nav` 未选中项 `@color/white`，白天不可见。
- **直播当前频道面板**：`activity_live.xml` 的 `liveSource` 图标 `ic_live_source` 全白填充，白天浅色面板上几乎不可见（截图放大确认）。

### 方案

1. `menu_vod.xml`：4 处 `app:iconTint="@color/white"` → `?attr/colorOnSurface`。
2. `selector_nav.xml`：`state_checked="false"` 由 `@color/white` → `?attr/colorOnSurfaceVariant`（选中仍为 `?attr/colorPrimary`）。
3. `selector_text.xml`：默认项由 `@color/white` → `?attr/colorOnSurface`（选中/勾选仍为 `?attr/colorOnSecondaryContainer`）。
4. `selector_live_text_classic.xml`：默认项由 `@color/white` → `?attr/colorOnSurface`。
5. `selector_live_classic.xml`：默认项保留 `alpha="0.14"`，颜色由 `@color/white` → `?attr/colorOnSurface`（黑夜观感几乎不变，白天成为淡灰行底色）；选中项仍为 `alpha=0.85 ?attr/colorSecondaryContainer`。
6. `activity_live.xml`：`liveSource` 增加 `app:tint="?attr/colorOnSurfaceVariant"`。该图标只在 embedded 模式显示 —— `setLiveMenuOverlay(true)` 分支会把 `liveCurrent` 置为 `View.GONE`，所以静态 tint 不会影响菜单浮层（深色渐变）下的观感。

### 保留白字的部分（承载于深色播放器面板，故意不动）

`selector_video_text.xml`（`dialog_video_content.xml` → `shape_player_child_sheet_panel`）、`yellow.xml`（`style Control` 播放器控制按钮）、`selector_control_sheet_text.xml`、`selector_live_action_icon.xml`（`adapter_live.xml`），以及 `ic_control_*` / `ic_widget_*` / `ic_popup_*` / `ic_action_*` 等叠加在视频与弹层上的图标。

### 校验

- 6 个改动文件 XML 格式校验通过。
- 逐项核对 mobile 全部 8 个白色默认选择器的承载面：本次修改的 4 个位于白天浅色承载面；保留的 4 个位于深色播放器面板。
- 原生编译本地不可用，由 fork CI `:app:assembleMobileArm64_v8aRelease` 验证通过。

### 交付记录（1.0.60）

- 代码提交：`9aaac80f`（白天可读性，6 文件 `+10/-9`）、`c14eacc1`（版本号升到 1.0.60 / code 61，3 文件 `+5/-5`）
- CI：run `35540971547`（head_sha `c14eacc1`）**success**
- 产物：package `com.fongmi.android.tvceshi`，versionCode `61`，versionName `1.0.60`
- APK SHA256：`02d7dad94e2b5071bd47910868584a978d8be6daa50fb1c799f6d68664e69ec6`（141384715 字节）
- 上传：`https://tvbox.moliys.icu/apk/tvbox-moliys-bypass-test-1.0.60.apk`（HTTP 206，远端 141384715 字节）
- 站点版本：未改 site-src，`SITE_VERSION` 与 `config.json` `site.version` 维持 3.0.40
- 待办：真机确认 白天 影视主页工具栏图标 / 分类栏 / 底部导航 / 直播频道与分组文字 / 直播面板来源图标 全部清晰可见；黑夜观感与 1.0.59 一致

## 26. 原生壳调色板严格对齐站点（1.0.61）

反馈（附 3 张截图 + 站点 CSS 变量表）：夜晚模式的背景不是纯黑，而是偏蓝黑的「科技感」配色，并给出站点 `:root` 与 `body[data-theme="dark"]` 两套变量，要求原生壳配色与之对齐。1.0.60 的图标可见性修复已生效（截图 3 的搜索 / 最近观看 / 右上角三个点均为白色可见）。

### 排查结论

- **站点变量是权威来源**：`site-src/index.html:25-45`（`:root` 白天）与 `site-src/index.html:47-66`（`body[data-theme="dark"]` 黑夜）。与用户提供的变量表逐项一致。
- **原生壳黑夜底仍是纯黑**：`app/src/main/res/values-night/moliys_shell.xml:3` 的 `moliys_bg = #FF0F1115`，以及 `MoliysTheme.java:20` 的 `SHELL_DARK = 0xFF0F1115`。站点黑夜 `--bg` 是 `#0d1420`（蓝黑）。
- **黑夜中间色也是冷灰而非站点的蓝灰**：`values-night/moliys_theme.xml` 原为 `surface #161B22` / `surface_variant #1E2733` / `on_surface #E6EDF5` / `on_surface_variant #9AA9BB` / `outline #2B3948` 等，与站点 `--card #141f31` / `--panel #101a2b` / `--text #dbe6f2` / `--text2 #8fa6c4` / `--border #223650` 存在可见色偏。
- **下载管理页自带一套硬编码深色**：`DownloadActivity.java:37-42` 直接写死 `BG/CARD/TEXT/SUB/ACCENT/DANGER`，其中 `BG = 0xFF0F1115` 同为纯黑，且它在 `AndroidManifest.xml:173` 用 `@style/Theme.MoliysShell`，窗口底已是新色，根视图却用旧纯黑覆盖，会与其余页面不一致。

### 站点变量到原生属性映射

| 站点变量 | 黑夜取值 | 原生资源 | 白天取值 |
|---|---|---|---|
| `--bg` | `#0d1420` | `moliys_bg`（`values*/moliys_shell.xml`）+ `MoliysTheme.SHELL_DARK` | `#eef2f7` / `SHELL_LIGHT` |
| `--card` | `#141f31` | `moliys_surface` | `#ffffff` |
| `--panel` | `#101a2b` | `moliys_surface_variant` | `#e4ebf3`（`--border-soft`） |
| `--border` | `#223650` | `moliys_outline` | `#d4dbe5` |
| `--text` | `#dbe6f2` | `moliys_on_surface` | `#1a2b3d` |
| `--text2` | `#8fa6c4` | `moliys_on_surface_variant` | `#7a8fa3` |
| `--accent` / `--accent-2` | `#4a9eff` | `moliys_primary` | `#2563eb` |
| `--tagbg` | `rgba(74,158,255,.16)` | `moliys_primary_container` = 叠加到 `--card` 后的实色 `#1d3352` | `#d8e8f8` |
| `--tagtext` | `#7fb0e8` | `moliys_on_primary_container` | `#3a5a7a` |
| `--urlborder` | `#1e2f47` | `moliys_secondary_container` | `#e4ebf3` |
| `--text-strong` | `#eef4fb` | `moliys_on_secondary_container` | `#0a1a2b` |

### 方案

1. `values-night/moliys_shell.xml`：`moliys_bg` `#FF0F1115` → `#FF0D1420`。
2. `values/moliys_shell.xml`：`moliys_bg` `#FFF1F5FA` → `#FFEEF2F7`（对齐 `--bg`）。
3. `MoliysTheme.java`：`SHELL_LIGHT = 0xFFEEF2F7`、`SHELL_DARK = 0xFF0D1420`，与资源常量保持一致。
4. `values-night/moliys_theme.xml`：11 个颜色按上表黑夜列全部替换。
5. `values/moliys_theme.xml`：`on_surface_variant` / `primary_container` / `on_primary_container` / `secondary_container` / `on_secondary_container` 按上表白天列对齐；`surface` / `outline` / `on_surface` / `primary` 本已一致保持不变。
6. `DownloadActivity.java`：6 个常量对齐站点黑夜列 —— `BG=--bg`、`CARD=--card`、`TEXT=--text`、`SUB=--text2`、`ACCENT=--accent`、`DANGER=--bad`（`#F87171`）。

### 一个刻意的例外

白天 `moliys_surface_variant` 取 `--border-soft #e4ebf3`，未取 `--panel #f8fafd`。原因是该色承载「未选中态」：`selector_live.xml` 的默认项与 `bg_year.xml` 都直接用它，而它们在白天位于 `colorSurface = #ffffff` 之上；若取 `#f8fafd` 会与白色卡片几乎无差别导致未选中态看不清。黑夜侧不存在此问题（`--panel #101a2b` 本就比 `--bg #0d1420` 亮），故黑夜 `surface_variant` 严格取 `--panel`。

### 校验

- 4 个 XML 全部通过 `xml.etree.ElementTree` 良构解析。
- 逐项断言：白天 11 项 + 黑夜 11 项颜色等于站点对应变量，不匹配数 0；`values-night` 内 `0F1115` 残留为 False。
- `DownloadActivity.java` 6 个常量逐项断言通过，文件内 `0F1115` 残留为 False。
- 原生编译本地不可用，由 fork CI `:app:assembleMobileArm64_v8aRelease` 验证通过。

### 交付记录（1.0.61）

- 代码提交：`330e225e`（原生壳调色板对齐站点，5 文件 `+21/-21`）、`2c216117`（下载页硬编码配色，1 文件 `+6/-6`）、`705655c2`（版本号升到 1.0.61 / code 62，3 文件 `+5/-5`）
- 恢复标签：`recovery/1.0.61-dark-palette/20260921063928-330e225e509a`、`recovery/1.0.61-download-palette/20260921064011-2c216117715f`、`recovery/1.0.61-version/20260921064137-705655c2084b`
- CI：run `35542526644`（head_sha `705655c2`）**success**（attempt 1）
- 产物：package `com.fongmi.android.tvceshi`，versionCode `62`，versionName `1.0.61`
- APK SHA256：`8a4ccda67aec36a1222d6a8824049df19ba7f4c323217950603b7ec46bf56c21`（141384715 字节）
- 上传：`https://tvbox.moliys.icu/apk/tvbox-moliys-bypass-test-1.0.61.apk`（HTTP 206，远端 141384715 字节）
- 站点版本：未改 site-src，`SITE_VERSION` 与 `config.json` `site.version` 维持 3.0.40
- 待办：真机确认 黑夜 影视主页 / 设置 / 直播 / 下载管理 的底色为蓝黑 `#0d1420` 而非纯黑、卡片为 `#141f31`、边框呈蓝灰；白天观感与 1.0.60 一致（仅 chip / 标签色微调）

## 27. F1 自动站点（AI 识别建站）上线（1.0.62）

按用户已批准的移植顺序，第一项「自动站点」在本版落地。功能目标是让用户只填一个网站地址，由 app 自己判断出可用的采集接口并加入站点列表，不需要用户手工找 `api`。设计依据与完整决策见当前工作区 内的 `docs/F1-autosite-ai-site.md`。

### 实现方式

- **入口**：原生「增强设置」页新增「AI 自动站点」行（与 `shellProxy` 同级，紧邻其后），mobile 与 leanback 两套布局/Java 同步；右侧摘要显示已添加站点数，为 0 时显示「未添加」。
- **识别顺序（D7 探测优先）**：输入本身像接口 → 直接探测建站；输入是完整 JSON 配置 → 直接导入；抓首页 HTML 找线索 → 直接建站；都不中且用户已配置 AI → 才把清洗后的页面内容发给模型；都不中且未配置 AI → 提示需要先配置 AI。即「能用探测解决就不调模型」，省额度也更快。
- **模型调用**：OpenAI 兼容 `POST`，`Authorization: Bearer`，`json_object` 模式 + `temperature 0.2`，超时 60s、失败重试 1 次；服务端不支持 `response_format` 时自动退回纯文本模式。送出前 HTML 去 `script`/`style` 并截断 100KB。
- **密钥与隐私**：AI Key **不预置**，用户自填，只存 `Prefers`，不回显、不打日志、不进站点配置；首次使用需勾选知情同意。API 地址与模型名给出可修改的占位默认值。
- **落盘**：站点写入独立分组配置 `filesDir/moliys_ai_sites.json`，key 为 `moliys_ai_` + host/api 摘要，与既有「采集站」的 `moliys_cai` 互不冲突；识别失败不改动已有配置。
- **不碰网页**：整条链路走原生，因此**不需要**任何 `@JavascriptInterface` 桥接，也不需要重打 `site.pak`。

### 新增文件

- `app/src/main/java/com/moliys/tvbox/AiSite.java`（探测、校验、配置读写、D7 编排）
- `app/src/main/java/com/moliys/tvbox/AiSiteSetting.java`（AI 偏好读写）
- `app/src/main/java/com/moliys/tvbox/AiSiteClient.java`（OpenAI 兼容调用）
- `app/src/main/java/com/fongmi/android/tv/ui/dialog/AiSiteDialog.java` + `dialog_ai_site.xml` + `adapter_ai_site.xml`
- 三语 strings 各新增 23 个 AI 键

### 校验

- S1 harness 48 断言、S2 harness 108 断言（对本地 HttpServer 真实 POST 往返）、S3a harness 36 断言，全部通过。
- S3b：21 个布局 id 覆盖全部 binding 字段引用；13 个布局字符串键 + 11 个 Java 字符串键在三语 strings 全部存在且占位符与调用参数匹配；缺失的颜色与样式经比对确认与已发布模板 `dialog_shell_proxy.xml` 同源（构建期依赖提供）。
- S4：mobile 布局 34 个 id 与 Java 34 个 `mBinding` 引用 1:1 全命中，leanback 35 与 35 全命中；两个布局 XML 良构。
- 原生编译本地不可用（无 build-tools），由 fork CI 验证：S3b 的 run `35548102456` 与 1.0.62 的 run `35548522661` 均 **success**，即对话框、两个布局、字符串资源与 ViewBinding 生成均通过真实 AGP 构建。

### 交付记录（1.0.62）

- 代码提交：`2dc8395e`（S1 基础层 `+409`）、`1804a4e7`（S2 AI 客户端 `+391`）、`08151a0c`（文档：记录全 app trust-all TLS 已知风险与不修理由）、`e48ee340`（S3a D7 探测编排 `+63`）、`ad734b58`（S3b 对话框与两个布局 `+739`）、`b9237517`（S4 设置页入口行 `+84`）、`29a20a9e`（升版本 1.0.62 / code 63，3 文件 `+5/-5`）
- 恢复标签：`recovery/F1-autosite-s2/20260921080733-1804a4e7bba4`、`recovery/F1-tls-risk-note/20260921081050-08151a0c4f29`、`recovery/F1-autosite-s3a/20260921081627-e48ee34075bf`、`recovery/F1-autosite-s3b/20260921083335-ad734b587ee5`、`recovery/F1-autosite-s4/20260921084018-b92375177b09`、`recovery/F1-autosite-s5/20260921084154-29a20a9ed979`
- 产物：package `com.fongmi.android.tvceshi`，versionCode `63`，versionName `1.0.62`，App 名 `过包名版本测试版`
- APK SHA256：`34155e9b6e1ffb128d9aa5c269eecdece9b251ab921cbc15a73d67050cc7126d`（141399863 字节）
- 上传：`https://tvbox.moliys.icu/apk/tvbox-moliys-bypass-test-1.0.62.apk`（HTTP 206，远端 141399863 字节）
- 站点版本：未改 site-src，`SITE_VERSION` 与 `config.json` `site.version` 维持 3.0.40
- 待办：真机验收 D7 四个分支（像接口的输入直接建站、首页 HTML 探测、完整 JSON 配置导入、需 AI 时提示先配置）与站点增删；验收通过后按 §8 S6 复制到其余 5 个版本

## 28. F1 三缺陷修复（1.0.63）

1.0.62 真机使用中暴露出三个问题，本版做定点修复，不改功能范围。

- **裸域 AI 地址**：用户常只填服务根地址（如 `https://api.deepseek.com`），请求会打到不存在的路径上。改为按后缀补全成 `chat/completions` 端点（已带 `/v1` 补 `/chat/completions`，已带端点则原样）。
- **失败看不清原因**：HTTP 报错只给状态码，看不出是哪一步失败。`HttpError` 现在携带请求 URL 与响应体摘要；同时 `message(role, content)` 统一请求体构造，去掉整段 JSON 导入分支遗留的重复逻辑。
- **对话框新增站点后不生效**：识别成功后没有重载配置，影视主页看不到新站。`AiSiteDialog` 的 `changed` 字段改名 `added` 并只在真的新增时才 `reloadConfigs()`，删除源同样触发重载。

交付记录（1.0.63）：代码提交 `f81ef270`（三缺陷修复）、`63a77c63`（升版本 1.0.63 / code 64，3 文件）；验证 harness 38 断言全过；fork CI run `35551439152` success；产物 package `com.fongmi.android.tvceshi` / versionCode `64` / versionName `1.0.63`，SHA256 `429468e8fb370c8c965a490fbcfa7de68daa49405b5d1b20a8bf5781acebaadc`（141399699 字节），已上传 `https://tvbox.moliys.icu/apk/tvbox-moliys-bypass-test-1.0.63.apk`。

**本版方向随后被用户纠正**：AI 只是「从 HTML 里挑一个现成接口」，对没有 maccms 接口的站无能为力。修订方向见 §29。

## 29. F1 方向修正：真·写源流水线（1.0.64）

用户指出 1.0.62/1.0.63 的识别思路不成立：**AI 应该读全站样本后写出一个可执行的爬虫源**，而不是在页面里找一个现成接口。目标站没有 `api.php/provide/vod` 时也要能建站。设计修订与全部决策见当前工作区 内的 `docs/F1-autosite-ai-site.md` §12。

### 修订要点

- **加站语义**：新站以壳子既有「自定义源」条目（`CustomCspSetting` 的 `Item`）落盘，在配置加载时注入**当前配置的站点列表**，原源（如 饭太硬）与排序不变；不再切到独立分组，也不再切换用户当前接口配置。
- **产物形态**：一个**本地源码文件**（`.py` 或 `.js`），由壳子自带 `/file/` 路由提供给加载器；`.py` 走 Chaquopy，`.js` 走 QuickJS，二者能力都在壳子里，无需新增运行时。
- **生成语言由 AI 自行判断**，界面不提供语言开关；要求模型在源码首行写 `#!lang=py` 或 `//!lang=js`，解析层据此定扩展名并剥离该行。
- **四步流水线**：①设备端探测（首页/分类/详情/播放/搜索 6 页 + 1 次播放地址确认，不耗 AI 额度）②AI 写源（单文件全文，不再用 `response_format`）③设备端按**生产同路径**加载并跑自检契约 ④通过后写注册表并重载。
- **诚实性底线**：自检不通过会把失败原因连同上一版源码回灌给 AI 修 1 轮；仍不通过就明确报「这个站做不出来」并且**不写注册表**，用户看到的站点列表保持原样。
- **保留捷径**：目标站自带苹果CMS JSON 接口时仍直接加为 type 1 站点，跳过 AI 与自检。

### 实现阶段

| 阶段 | 内容 | 提交 |
|---|---|---|
| S6-r2a | `AiSite` 改走自定义源注册表（删分组配置/`activate`），`AiSiteDialog` 跟着调整 | `692f96f2` |
| S6-r2b | 新增 `AiSiteProbe` 探测阶段 | `a5ed6777` |
| S6-r2c | `AiSiteClient.writeSpider` 写源（提示词契约、语言标记、失败回灌） | `82b401af` |
| S6-r2d | 新增 `AiSiteSelfTest` 设备端自检（5 步契约） | `21adcac2` |
| S6-r2e | 对话框串联四步 + 进度/失败提示 + 三语文案 | `6a2bdd08` |
| S6-r2f | 升版本 1.0.64 / code 65 → fork CI → 下载校验 → 上传 → 真机复验 | 本节 |

### 关键实现口径

- **候选源码先落文件、注册表后写**：自检必须按生产路径加载，所以第 3 步先把候选源码写到 `CustomCspSetting.file(id, name)`（`AiSite.stageSource`，只写文件不动注册表），自检通过后才 `addSite` 写注册表；自检失败只留一个会被下次识别覆盖的候选文件，站点列表不变。
- **修正轮换文件名与站点 key**：`PyLoader`/`JsLoader` 按站点 key 缓存 Spider，QuickJS 的 `Module.fetch` 还按 URL 缓存模块正文，沿用同一 key/URL 会把上一版坏源码喂回自检，所以第 1 轮用 `spider.<lang>` / key=`id`，修正轮用 `spider-2.<lang>` / key=`id#2`。
- **文件访问权限前置**：源码落在外部存储的自定义源目录，未授权时直接提示 `setting_custom_csp_permission_required`，不进流水线。
- **无搜索能力的站点**：探测未发现站内搜索时 `searchable=0`，自检第 6 项记「不适用」而不是判失败。

### 校验

- 本地 harness 累计 193 断言全过：r2a 49（注册表落盘/显式 id/搜索标记/候选文件只写不注册）、r2b 30（探测 6 页与 SPA 脚本、搜索模板、播放确认、`isSearchable`/`homeBody`）、r2c 47（对本地 mock AI 端点端到端：源码全文、语言标记与围栏解析、失败回灌重试、修正轮 4 条消息、Key 不进请求体、仍不发 `response_format`）、r2d 39（替身 Spider 跑 5 步自检的每个通过与失败分支、生产加载接线）、r2e 28（候选文件落盘与覆盖、显式 id、`searchable` 0/1、非 `.py`/`.js` 拒绝）。
- 4 份 XML（对话框布局 + 三语 strings）良构；四步文案三语各 1 条。
- `AiSiteDialog` 与 `com.moliys.tvbox` 包单文件 `javac` 语法检查无语法级错误（仅缺 androidx/AGP 依赖符号，属本地无 gradle 缓存的正常现象）。
- 原生编译由 fork CI 验证（本地无 build-tools）；真机验收在本版进行。

### 交付记录（1.0.64）

- 代码提交：`692f96f2`、`a5ed6777`、`82b401af`、`21adcac2`、`6a2bdd08`、`80856af8`（升版本 1.0.64 / code 65）、`cb7ecba7`（修 CI 编译失败）
- **编译失败与修复**：首次 CI（run `35558329768`）在 `AiSiteSelfTest` 报 14 处 `unreported exception JSONException` —— 自检结果的四个构造辅助方法直接 `json.put(...)`，而真机/CI 上 `org.json.JSONException` 是受检异常。改为统一走 `put(json,key,value)` 兜底（`catch (Throwable)` 吞掉），提交 `cb7ecba7`；CI run `35559451141` success。
  - 本地此前漏检的原因：用 `android.jar` 单独编译时报了「类名与文件名不符」这类错误，javac 因此跳过了流分析；同时另一路检查把 `org-json.jar` 排在前面，而那份 `JSONException` 继承 `RuntimeException`，受检异常检查被静默绕过。两点已记入 `.monkeycode/MEMORY.md`。
- 恢复标签：`recovery/F1-autosite-r2a/20260921104623-692f96f29233`、`recovery/F1-autosite-r2b/20260921105536-a5ed677782d4`、`recovery/F1-autosite-r2c/20260921110050-82b401afb346`、`recovery/F1-autosite-r2d/20260921111312-21adcac20535`、`recovery/F1-autosite-r2e/20260921113547-6a2bdd08e526`
- 站点版本：未改 site-src，`SITE_VERSION` 与 `config.json` `site.version` 维持 3.0.40
- 交付记录：CI run `35559451141` success；产物 package `com.fongmi.android.tvceshi` / versionCode `65` / versionName `1.0.64`，minSdk 24 / targetSdk 28，SHA256 `673f80db1bae4ad7b01a2b152636ca495f442ba4e359781ac474a1db7bf005db`（141419871 字节），已上传 `https://tvbox.moliys.icu/apk/tvbox-moliys-bypass-test-1.0.64.apk`（HTTP 206）
- 恢复标签：`recovery/F1-autosite-r2f/20260921113953-80856af85b31`、`recovery/F1-autosite-r2f-fix1/20260921120018-cb7ecba754f6`
- 待办：真机验收「随便一个影视站 → 生成可跑源」全链路（探测样本→写源→自检→落盘→进站可播）；验收通过后按 §8 S6 复制 F1 到其余 5 个版本

## 30. F1 写源实时进度 + 探测多 UA 重试（1.0.65，未打包）

1.0.64 真机复验反馈两件事：①普通影视站仍然建不出来（检测不出结构或抓不到内容），且写源过程是黑盒，不知道 AI 卡在哪一步；②用户怀疑「没结构」是 UA 被拦。本版只做这两点，不扩范围。

### 写源实时进度

- 新增 `AiSiteProgress`（`step(String)` + 空实现 `NONE`），探测/写源/自检三层都接受它并把「现在正在干什么」逐条回报。
- `AiSiteProbe`：`用「桌面 Chrome」打开首页` → `首页已抓到 N 字节` → `首页解析出 N 个链接` → `抓分类页`/`抓详情页`/`抓站内搜索` → `确认播放地址`；无分类/无搜索入口时明确说「没找到…（该项接下来记「不适用」）」。
- `AiSiteClient.writeSpider`：`把探测样本发给 AI 写第一版源` → `AI 给出一份 py 源，开始本机自检`；修正轮入口显示 `带着上一版的失败原因让 AI 重写`，重试显示 `AI 正在按失败原因重写（第 2 次）`。
- `AiSiteSelfTest`：`自检：加载这个源` → `自检：首页/分类页/详情页/站内搜索/播放地址` → `自检通过`；失败时进度停在出错那一步。
- 对话框把 `status(String)` 直接作为进度接收器（`this::status`，内部已切主线程），原有的分阶段标题文案保留在每阶段开始时先显示一次。

### 探测多 UA 重试

- 首页请求改为按 4 个 User-Agent 依次尝试：桌面 Chrome → 移动 Chrome → Android TV → iOS Safari；命中拦截页（Cloudflare 验证、403、`人机验证`、`请开启javascript` 等短页面特征）或空响应时换下一个，并把尝试过的 UA 名称写进进度（抓不到正文记「没拿到内容」，抓到拦截页记「被拦了（疑似需要验证）」，两者区分开）。
- 换 UA 成功后，分类/详情/播放/搜索/脚本所有后续请求复用命中的那个 UA。
- 请求预算从 7 提到 12（首页最多 4 次换 UA + 分类/详情/播放/播放确认/搜索各 1 + 脚本包最多 2 + 余量）。
- 空壳页面（首页链接 < 3 或可见文本 < 200 字）额外采集：最多 2 段内联脚本 + 最多 2 个外链脚本包（同域优先），给 AI 更多「真接口在哪」的线索。

### 校验

- 本地 harness 累计 224 断言全过：r2a 49、r2b 46（新增换 UA 命中、UA 被拦提示、打不开时逐 UA 留痕、后续请求复用 UA、6 类进度步骤齐全）、r2c 54（新增写源/修正轮进度文案）、r2d 47（新增自检逐步进度与失败停在出错步）、r2e 28。
- `check-moliys.sh` 编译检查 0 错误、0 受检异常错误（含新文件 `AiSiteProgress.java`）。
- `AiSiteDialog` 编辑前后 `javac` 错误直方图完全一致（仅缺 androidx/`R` 符号），无新增语法级错误。
- 原生编译由 fork CI 验证；真机验收在本版进行。

### 交付记录（1.0.65，待打包）

- 改动文件：`AiSiteProgress.java`（新增）、`AiSiteProbe.java`、`AiSiteClient.java`、`AiSiteSelfTest.java`、`AiSiteDialog.java` 与本文档；未改 site-src，站点版本维持 3.0.40。
- 待办：升版本 1.0.65 / code 66 → fork CI → 下载校验 → 上传 → 真机复验「多 UA 下能建站」与「进度实时可见」。
- 已知风险（受跟踪，未处理）：三层进度文案目前是硬编码简体中文，与 F1 既有运行时错误文案口径一致，英文/繁体界面下会显示简体。
