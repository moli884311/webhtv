# 过包名版本正式版 1.0.67 发布（移植测试版已验收功能）

## Recovery anchor

- 目标：把「过包名版本测试版」已验收的全部功能移植到正式版，升到 **1.0.67 / code 68**，出正式包并通过 `version-moliys.js` 强制更新下发。
- 验收标准：
  1. 正式版 `applicationId` 保持 `com.fongmi.android.tv`，`versionCode 68` / `versionName 1.0.67`。
  2. 站点 `site.pak` 与测试版一致，且 `UPDATE_MANIFEST_URL` 指向 `version-moliys.js`。
  3. CI 构建成功，产物 `versionCode 68` / `versionName 1.0.67`。
  4. 服务器 `version-moliys.js` 的 `versionCode`/`apkUrl`/`apkSize`/`apkSha256` 与新包实际值一致，`force: true`。
- 回滚：正式版回到 `65f8c732`（1.0.39/code40），服务器清单从 `version-moliys.js.bak-vc40-1067` 还原。
- 状态：**已发布**。CI `35675285056` success；APK 已上传；`version-moliys.js` 已指向新包并通过一致性校验。

## 交付记录

| 项 | 值 |
| --- | --- |
| 代码提交 | `61ef8e47`（移植 + 升版本，49 文件） |
| 恢复标签 | `recovery/port-official-1067/20260922091805-61ef8e47474b` |
| CI run | `35675285056` success（push `bypass-official` 触发） |
| 产物 artifact | `moliys-bypass-official-arm64`（id `10673360521`） |
| APK 路径 | `mobileArm64_v8a/release/mobile-arm64_v8a-bypass-official.apk` |
| 包名 / 版本 | `com.fongmi.android.tv` / code `68` / `1.0.67` |
| 大小 | 141419863 字节 |
| SHA256 | `60a028628f8d9555d4c1b040412521246d7da9cec9ba6eedf267f9611b080fab` |
| APK 公网地址 | `https://tvbox.moliys.icu/apk/tvbox-moliys-1.0.67.apk?v=68` |
| 清单 | `apk/version-moliys.js`（`force: true`）；旧清单备份 `apk/version-moliys.js.bak-vc40-1067` |

### 强制更新一致性校验（「不能有错」）

| 校验 | 结果 |
| --- | --- |
| 本地 APK sha256 | `60a0286…0fab` |
| 服务器 `sha256sum` | 同上，完全一致 |
| 服务器文件大小 | 141419863 |
| 清单 `apkSize` | 141419863（与文件一致） |
| 清单 `apkSha256` / `sha256` | 与服务器一致 |
| 公网清单可读 | HTTP 200，840 字节，BOM 保留，`versionCode 68` / `force true` |
| 公网 APK HEAD | HTTP 200，`content-length: 141419863` |
| 公网 APK 范围请求 | HTTP 206，1024 字节 |

强更链路成立：旧用户 code 40 → 清单 68 → 站点弹「发现新版本，需升级后使用」→ 应用内下载校验 sha256 → 同包名同签名覆盖安装。

## 回滚步骤

1. 服务器：`cp -a apk/version-moliys.js.bak-vc40-1067 apk/version-moliys.js`（恢复 1.0.39 清单），按需删除 `apk/tvbox-moliys-1.0.67.apk`。
2. 仓库：正式版 `git reset --hard 65f8c732`（或 revert `61ef8e47`）。

## 移植范围

来源：`沫离app/过包名版本测试版`（branch `bypass-test`，已回滚央视内置源）。

两版差异只有三处配置，其余源码可直接同步：

| 项 | 正式版 | 测试版 |
| --- | --- | --- |
| `applicationId` | `com.fongmi.android.tv` | `com.fongmi.android.tvceshi` |
| 工作流触发分支 | `bypass-official` | `bypass-test` |
| APK 后缀 / 产物名 | `bypass-official` | `bypass-test` |

同步内容（46 个文件差异 + 新增文件）：

- 授权门禁：`LicenseManager.java`、站点 `body.no-license` 相关 UI
- 导航重构与首页还原到 1.0.50 观感：`WebHomeChromeController`、`HomeActivity`、`selector_*`、`shape_live`、`styles.xml` 等
- F1 自动站点：`AiSite*`（7 个）、`AiSiteDialog`、`CaiSite`、`adapter_ai_site.xml`、`dialog_ai_site.xml`、`SettingEnhanceActivity/Fragment` 与对应 layout
- Exo 音频直通默认关闭：`ExoCompressedAudioDirectPolicy`、`ExoUtil`、`ExoPerformanceSetting`、`Setting`
- 主题/黑夜配色：`MoliysTheme.java`、`moliys_theme.xml`、`moliys_shell.xml`、`values-night/`、`CustomWallView`
- 站点：`assets/site.pak`（含 Telegram 新群链接 `https://t.me/+gICsSWRbbkJkNjhl`）

**未移植**：`BuiltinLive.java`（央视内置源，1.0.66 真机卡顿已回滚）。

## 校验

- `applicationId "com.fongmi.android.tv"`，`versionCode 68`，`versionName "1.0.67"`（`app/build.gradle`）。
- `Version.java` `CODE = 68` / `NAME = "1.0.67"`。
- 源码内无 `BuiltinLive` 残留，无 `ceshi` 残留。
- `site.pak` 解密校验：`index.html` 新 Telegram 链接出现 1 次、旧链接 0 次；`UPDATE_MANIFEST_URL` = `https://tvbox.moliys.icu/apk/version-moliys.js`。
- 代码与已通过 CI 的测试版 1.0.66 完全一致，差异仅在配置；决定性验证为本次 CI 构建。

## 已知限制

- 测试版（`com.fongmi.android.tvceshi`，code 67）本次不重出。其内嵌网页同样读 `version-moliys.js`，正式版发布后会提示更新并下载正式包，但包名不同会安装失败（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`）。用户已确认不重出测试版，需手动卸载测试版。
- 站点 `data/downloads.json` 的「沫离」条目仍为 1.0.39 且 `pkg` 为 `com.moliys.tvbox`（全功能版包名），与过包名版本不一致，本次不修改。
