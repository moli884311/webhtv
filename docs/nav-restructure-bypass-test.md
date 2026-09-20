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

## 7. 回滚

- 站点：`git revert` 对应提交后由 `site-src/` 重打 `site.pak`。
- 原生：`git revert` 对应提交，或恢复 `MainActivity.java` 中 `injectVideoEntry` 的悬浮按钮分支与 Bridge 方法。
