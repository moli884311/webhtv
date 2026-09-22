# 沫离全功能版正式版 1.0.71（删除影视按钮）

## Recovery anchor

- 目标：删除 App 内站点页注入的浮动「影视」按钮，升版本到 **1.0.71 / code 72**，出包并推送。
- 验收标准：
  1. 站点页不再出现浮动「影视」按钮。
  2. `applicationId` 保持 `com.moliys.tvbox`，`versionCode 72` / `versionName 1.0.71`。
  3. CI 构建成功；APK 上传；`version-moliys.js` 指向新包且 `force: true`。
- 回滚：`git revert` 本次提交（或回到 `80e97696`，1.0.39/code40）；服务器清单备份 `version-moliys.js.bak-vc68-1071`。
- 状态：已完成（含通道拆分，见下）。

## 交付

| 项 | 值 |
|---|---|
| commit | `60a03954` |
| 恢复标签 | `recovery/full-del-video-btn-1071/20260922103602-60a039546e09` |
| CI | run `35680154695` success |
| artifact | `moliys-full-official-arm64`(id `10674181972`) |
| APK | `com.moliys.tvbox` / code 72 / 1.0.71 / 141366243 B |
| SHA256 | `93a9aef940fa3c3ccf37dde42f63bb94a88d142b64091099ce7dc88ddcdd8fb7` |
| 下载 | `https://tvbox.moliys.icu/apk/tvbox-moliys-1.0.71.apk?v=72` |


## 改动

- `MainActivity.java`：删除 `injectVideoEntry(WebView)` 方法及其在 `onPageFinished` 中的唯一调用。该方法唯一作用就是向站点页注入浮动「影视」按钮（`mk('影视','110px',…)` → `TVBoxNative.openVideo()`）。网页端点「影视」不受影响；原生侧 `showVideoPanel` / `enterVideo` / `openVideoHome` 与 JS 桥 `TVBoxNative.openVideo()` 保留，避免影响站点页自身入口。
- `Version.java`：`CODE = 72` / `NAME = "1.0.71"`。
- `app/build.gradle`：`versionCode 72` / `versionName "1.0.71"`。
- `.github/workflows/build.yml`：`WEBHTV_RELEASE_TAG: moliys-1.0.71`。

## 校验

- 源码：`grep injectVideoEntry` 无残留；`MainActivity.java` 花括号/圆括号配平（248/248、598/598）；`BuildConfig` 仍被其它代码使用，导入不失效。
- APK：`classes.dex` 中注入标记 `__moliysTvEntry` 出现 0 次（按钮确实消失）；`TVBoxNative` JS 桥仍存在（仅删按钮、未动站点页入口）。
- 包信息：pyaxmlparser 确认 `com.moliys.tvbox` / code 72 / name 1.0.71。
- 服务器：远端 `sha256sum` 与本地一致；公网 HEAD `content-length` 141366243；范围请求 206。
- 清单：公网 `version-moliys.js` HTTP 200 / 452 B / 带 BOM，`versionCode 72`、`apkUrl` 指向 1.0.71、`force: true`。

## 遗留（未处理，等指示）

- **事故与恢复（2026-09-22）**：本次把 `version-moliys.js` 改为全功能版口径（code 72），而**四个版本**（全功能正式/全功能测试/过包名正式/过包名测试）的 `site.pak` 内 `UPDATE_MANIFEST_URL` **全部指向同一个 `version-moliys.js`**，导致已完成交付的过包名版本（`com.fongmi.android.tv`，code 68）被强制提示更新到包名不符的 APK。
  - 已恢复：`cp -a version-moliys.js.bak-vc68-1071 version-moliys.js`，公网复核 HTTP 200 / 840 B / `versionCode 68` / `versionName 1.0.67` / `apkUrl` 指向 `tvbox-moliys-1.0.67.apk`。
  - 保留未删：`tvbox-moliys-1.0.71.apk` 仍在服务器 `apk/` 目录，但当前无任何清单指向它。
- **待决策**：全功能版（`com.moliys.tvbox`）需要自己的更新通道。可选：(A) 改全功能版 `site.pak` 的 `UPDATE_MANIFEST_URL` 为独立清单（如 `version-full-official.js`）并重新出包；(B) 全功能版不做 OTA；(C) 继续共用一份清单。
- 全功能版 `site.pak` 内站点仍为**旧 Telegram 链接**，本次未改。

## 拆通道（用户选定方案 A，2026-09-22）

- 改 `app/src/main/assets/site.pak` 内 `index.html` 的 `UPDATE_MANIFEST_URL`：
  `version-moliys.js` → **`version-full-official.js`**，重新打包 site.pak。
- 重打包口径：用 `过包名版本测试版/tools/repack_site.py --no-fetch`（不拉服务器数据）；
  基线=改动前 pak，78 个条目逐个解密比对，**仅 `index.html` 一行不同**，其余字节一致；站点旧 TG 链接保持不变。
- 服务器侧：新建 `version-full-official.js`（全功能版专属），`version-moliys.js` 保持过包名版本 code 68 不动。
