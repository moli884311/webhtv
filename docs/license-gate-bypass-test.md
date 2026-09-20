# 影视授权门禁（过包名版本测试版）

## 目标 / 完成标准

只有经 QQ 机器人授权且在有效期内的设备，才显示并可打开「影视」；未授权或过期时自动隐藏。
连点站点「关于」标签 6 次可查看设备码与授权状态。管理员在群内用机器人指令设置到期时间。

- 目标版本：`过包名版本测试版`（`com.fongmi.android.tvceshi`），验证通过后同步 `过包名版本正式版`。
- 验收：CI 出包成功；未授权设备无「影视」按钮，连点「关于」6 次弹授权面板；群内 `授权 <设备码> 30` 后按钮出现且可进入；到期后按钮消失。

## 方案（混合：在线校验 + 签名 + 设备绑定 + 短时缓存）

1. App 由系统设备标识（ANDROID_ID）稳定派生 10 位设备码（去除易混字符），清除数据/卸载重装后不变，展示于授权面板。
2. 用户把设备码发到群，管理员回复 `授权 <设备码> <天数|YYYY-MM-DD|永久> [包名]`。
3. 机器人把记录写入 `/www/wwwroot/tvbox_licenses.json`（站点目录外，web 不可访问）。
4. App 联网请求 `https://tvbox.moliys.icu/auth_check.php`，响应含 `{auth,exp,srv,sign}`。
5. `sign` = RSA‑SHA256(`d|p|auth|exp|srv`)，App 内嵌公钥验签；密钥不落 App。
6. 验签通过后缓存 `auth/exp/srv/recv_elapsed`；以「服务器时间 + 单调时钟增量」估算当前时间，
   改系统时间无法续命；断网时可用缓存直到 `exp`。
7. 只有 `isAuthorized()` 为真才注入「影视」按钮；`Bridge.openVideo()` 再做一次原生校验。
8. 真正的护城河后续放在服务端（授权才对 `list.txt`/`api/*.json` 放行），当前为第一道门。

## 变更文件

- App：`app/src/main/java/com/moliys/tvbox/LicenseManager.java`（新增）、`MainActivity.java`、`Version.java`（42 / 1.0.41）
- 构建：`.github/workflows/build.yml`（tag `moliys-1.0.41`）
- 服务器：`server/auth_check.php`、`server/nginx-location.conf`、`server/README.md`
- 机器人（站点外，`/opt/qqbot`）：`framework/license.py` 新增，`framework/__init__.py`、`bot.py`、`framework/events.py` 挂载

## 服务器落地

- RSA‑2048 私钥 `/www/wwwroot/tvbox_license_rsa.key`（640 root:www，站点目录外）；公钥见 `server/README.md`，指纹 `fb4d5412…0dc253`。
- 授权存储 `/www/wwwroot/tvbox_licenses.json`（644）；缺省 `{"devices":{}}`。
- nginx：80/443 两个 server 块各加 `location = /auth_check.php { fastcgi_pass unix:/tmp/php-cgi-73.sock; … }`（镜像 `changelog.php`）。配置备份 `tvbox.moliys.icu.conf.bak-license`。
- 机器人：systemd `qqbot.service`；`systemctl restart qqbot`。

## 已记录验证

- `auth_check.php`：未授权 `auth=0`；写入记录后 `auth=1`；签名用内嵌公钥 openssl 验签 `Verified OK`；公钥指纹与服务器一致。
- 机器人：`py_compile` 通过，重启后 `on_ready` 正常，无 import 错误；模块 `_parse_expiry` 对 `30`/`YYYY-MM-DD`/`永久`/非法输入均正确，`_save` 写库后 `auth_check.php` 返回 `auth=1` 且验签通过；验证后已清空回 `{"devices":{}}`。
- App：静态复核通过（方法无重复、括号平衡）；CI `35488895915` success，产物 versionCode 41 / versionName 1.0.40 / `com.fongmi.android.tvceshi`。

## 交付产物

- APK：`https://tvbox.moliys.icu/apk/tvbox-moliys-bypass-test-1.0.40.apk`
- 大小 141366271，SHA-256 `38e462cd6b03a851f12045a05d058892356bc1cf183a39ec3f0db2f37639cf35`（含弹窗文案『把设备码发到群里，等待管理员回复』）
- 注意：不修改共享的 `/apk/version-moliys.js`（正式版 OTA 用），避免把正式版用户导向本测试包。


## 回滚

- App：回滚本提交（`Version` 退回 40 / 1.0.39）。
- 服务器：`cp -a /www/wwwroot/tvbox.moliys.icu/auth_check.php.bak …`；nginx 用 `…conf.bak-license` 覆盖后 `nginx -t && systemctl reload nginx`；删除 `auth_check.php`。
- 机器人：`framework/__init__.py.bak-license`、`bot.py.bak-license`、`framework/events.py.bak-license` 覆盖回去，删 `framework/license.py`，`systemctl restart qqbot`。

## 待办

- CI 出包后：上传 APK、更新清单、用户真机验证；再同步过包名正式版。
- 第二阶段：服务端接口数据（`list.txt` / `api/*.json`）按授权放行。
