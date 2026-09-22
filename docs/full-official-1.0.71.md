# 沫离全功能版正式版 1.0.71（删除影视按钮）

## Recovery anchor

- 目标：删除 App 内站点页注入的浮动「影视」按钮，升版本到 **1.0.71 / code 72**，出包并推送。
- 验收标准：
  1. 站点页不再出现浮动「影视」按钮。
  2. `applicationId` 保持 `com.moliys.tvbox`，`versionCode 72` / `versionName 1.0.71`。
  3. CI 构建成功；APK 上传；`version-moliys.js` 指向新包且 `force: true`。
- 回滚：`git revert` 本次提交（或回到 `80e97696`，1.0.39/code40）；服务器清单备份 `version-moliys.js.bak-vc68-1071`。
- 状态：进行中。

## 改动

- `MainActivity.java`：删除 `injectVideoEntry(WebView)` 方法及其在 `onPageFinished` 中的唯一调用。该方法唯一作用就是向站点页注入浮动「影视」按钮（`mk('影视','110px',…)` → `TVBoxNative.openVideo()`）。网页端点「影视」不受影响；原生侧 `showVideoPanel` / `enterVideo` / `openVideoHome` 与 JS 桥 `TVBoxNative.openVideo()` 保留，避免影响站点页自身入口。
- `Version.java`：`CODE = 72` / `NAME = "1.0.71"`。
- `app/build.gradle`：`versionCode 72` / `versionName "1.0.71"`。
- `.github/workflows/build.yml`：`WEBHTV_RELEASE_TAG: moliys-1.0.71`。

## 校验

- `grep injectVideoEntry` 无残留；`MainActivity.java` 花括号/圆括号配平（248/248、598/598）；`BuildConfig` 仍被其它代码使用，导入不失效。
- 决定性验证：本次 CI 构建。

## 背景（重要）

`version-moliys.js` 是**全功能版**（`com.moliys.tvbox`）的更新清单：全功能版 `site.pak` 内 `UPDATE_MANIFEST_URL` 指向它。上一次任务把它改指向过包名版本（`com.fongmi.android.tv`，1.0.67，code 68），导致全功能版用户会被引导下载包名不符的 APK。本次把清单改为指向全功能版 1.0.71，恢复正确指向。
