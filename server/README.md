# 影视授权门禁 — 服务器侧

## 组成

| 文件 | 位置 | 说明 |
|---|---|---|
| 私钥 | `/www/wwwroot/tvbox_license_rsa.key` | RSA-2048，`640 root:www`，站点目录外 |
| 授权存储 | `/www/wwwroot/tvbox_licenses.json` | `{"devices":{"<设备码>":{"exp":<unix>,"pkg":"","by":"","at":0}}}`，`644` |
| 校验接口 | `/www/wwwroot/tvbox.moliys.icu/auth_check.php` | 本目录 `auth_check.php` 上传 |
| nginx | `/www/server/panel/vhost/nginx/tvbox.moliys.icu.conf` | 加 `location = /auth_check.php`（见 `nginx-location.conf`），两个 server 块都要加 |

## 部署步骤

1. 生成密钥（仅首次）：

```bash
openssl genrsa -out /www/wwwroot/tvbox_license_rsa.key 2048
chmod 640 /www/wwwroot/tvbox_license_rsa.key
chown root:www /www/wwwroot/tvbox_license_rsa.key
openssl rsa -in /www/wwwroot/tvbox_license_rsa.key -pubout   # 公钥，填入 App LicenseManager
```

2. 初始化授权存储：

```bash
printf '{"devices":{}}' > /www/wwwroot/tvbox_licenses.json
chmod 644 /www/wwwroot/tvbox_licenses.json
```

3. 上传 `auth_check.php` 到站点根目录并 `chown www:www`、`chmod 644`。
4. 在 nginx 配置两个 server 块内按 `nginx-location.conf` 各加一段，然后：

```bash
nginx -t && systemctl reload nginx
```

5. 自测：

```bash
curl -s "https://tvbox.moliys.icu/auth_check.php?d=TESTDEV01&p=com.fongmi.android.tvceshi&v=41"
```

## 公钥（内嵌到 App）

```
-----BEGIN PUBLIC KEY-----
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA0hYx6BjDpBNp8NBxosKx
PuF+Q18oxX7Y4IE8O7ViVwvrLjxM4H5wtKsSA7QVT1NmgGNZ2wyFvmm9AWSqssBH
+m/Xl8dE2P2xzMC4bkCutnanTitWY445yZobELV7WSWqYZk5TDaoX4PuRoC/KKTJ
+6uGKDh2/VZLlnny6rgtESJlDa6Jdi0cxAwdQN3dHzLG8YGeX5lWauvWmmj2SKDb
n4eL9tUs3yLow62HiPE+VAkSwMdg5kSVKq9MfpFGThj54WfhTmPcfeCqyRe8+BKz
Dhvpj4wcQNyCTptfX4tiu6CCaocMdNblIBNEhCUpjnCsVZ74lgDdtbbjlmys3G3j
SQIDAQAB
-----END PUBLIC KEY-----
```

指纹：`fb4d54123aba49eb835470df7f0d8eabd169bdf692db3411b83079dda90dc253`

## 机器人指令（`/opt/qqbot`）

```
授权 <设备码> <天数|YYYY-MM-DD|永久> [包名]
解授权 <设备码>
授权列表
我的授权 <设备码>
```

管理员复用「关机」白名单 `owners.json`。修改 `framework/license.py` 后：

```bash
cd /opt/qqbot && venv/bin/python3 -m py_compile framework/license.py
systemctl restart qqbot
```
