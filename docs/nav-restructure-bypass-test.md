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

## 12. 回滚

- 站点：`git revert` 对应提交后由 `site-src/` 重打 `site.pak`。
- 原生：`git revert` 对应提交，或恢复 `MainActivity.java` 中 `injectVideoEntry` 的悬浮按钮分支与 Bridge 方法。
