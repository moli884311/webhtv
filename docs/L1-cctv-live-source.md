# L1 央视全频道内置直播源

## Recovery anchor

- 目标：不依赖央视频 guard，自建一份「能播才留」的央视直播源，托管到自有站点并作为 App 直播页兜底。
- 验收标准：
  1. `tools/gen_live_cctv.py` 只用标准库，逐条走「主清单 → 子清单 → 首个分片」校验，图片/网页/占位分片一律判失败。
  2. 产出 TVBox txt 格式，频道名规范化为 `CCTV-1..CCTV-17`，每频道多条线路，官方 CDN 优先。
  3. 文件托管在 `https://tvbox.moliys.icu/tvbox/live/央视.txt`，由宝塔计划任务定时刷新。
  4. App 直播页在未配置直播源时自动指向该文件；用户改过不覆盖（第 3 步，独立单元）。
- 状态：第 1、2 步完成（本文件所在提交）。第 4 步待做。
- 下一步：App 端 `LiveConfig` 兜底 + 升版本出包。

## 背景与目标

用户要求「央视全频道」，且明确不走逆向央视频 Ysp guard 协议（平台红线 + 自建口径）。
因此改为：用公开、可直连的线路自建校验型生成器，产出自己的央视源。

## 最佳实践调研（证据类）

| 证据类 | 结论 | 影响 |
| --- | --- | --- |
| 逆向上游源码 | 星落 `Lcom/fongmi/android/tv/server/process/Ysp;` 靠重实现 guard（TEA + 签名 + 自定义 base64）换取 `bkliveinfo.ysp.cctv.cn` 的播放地址 | **拒绝采用**：属绕过平台风控，且与用户既定口径冲突 |
| 官方规范/文档 | TVBox 直播 txt 格式：`分组名,#genre#` + `频道名,url1#url2`，单条可加 `url|Key=Value&Key2=Value2` 头 | 直接决定输出格式 |
| 本仓库现有实现 | `api/parser/LiveParser.java` 的 `txt()` / `Setting` 就是该格式的解析器；`Setting.headers()` 需要 `=` 分隔（非 `@`） | 输出用 `=`，不用常见但本项目不认的 `@` |
| 公开线路实测 | 央视官方低延迟 CDN `ldncctvwbcd` 仅真活 CCTV-1 / CCTV-13；移动 IPTV `tsfile/live` 覆盖 CCTV-1..15；iptv-org 等公开列表大面积失效 | 候选池 = 官方 CDN + 移动 IPTV + 自有站点既有直播源 |
| 成熟相关项目 | iptv-org、taksssss/tv 等列表作为补充候选，但不作为权威来源（未校验） | 仅作挖掘输入，不直接采信名称 |

未采用的类：学术论文/基准报告 —— 该问题为「线路可用性」工程问题，无对应研究结论可决定设计。

## 当前实现审查（改动前的调用/数据流）

- `Config.live()`（`bean/Config.java:95`）：`findOne(1)` 为空则 `create(1)`，即**默认空配置** → App 直播页默认没有任何频道。
- `LiveConfig.get().init().load()`（`api/config/LiveConfig.java:84`）：读取 `Config.live()` 的 URL 并解析；空配置时只有空 `lives`。
- `LiveConfig.load(cfg, Callback)`：`MainActivity.openLiveSource(name,url)`（`com/moliys/tvbox/MainActivity.java:982`）已用它加载网页端「打开」的直播源；站点直播源走这条路，**不依赖内置默认**。
- `api/parser/LiveParser.java`：`txt()` 支持 `#genre#` 分组、`#` 多线路、`|` 头；`isMetaChannel()` 会跳过 `更新时间…` 行。

结论：内置默认源只需在「`Config.live()` 为空」时补一个默认 URL，不影响已有网页端打开直播源的路径。

## 方案对比

| 方案 | 说明 | 判定 |
| --- | --- | --- |
| 不改 | 直播页继续为空，用户自己找源 | 不满足「全频道内置」需求 |
| 逆向 Ysp guard（星落做法） | 复刻星落本地 Ysp 服务，直连央视频拿官方源 | **否决**：绕过平台风控，且用户明确不采用 |
| 自建校验型生成器（本方案） | 公开线路 + 逐条端到端校验 + 定时刷新 | **采用** |

取舍：拿不到央视频官方低延迟全量源（含 CCTV-16、CGTN、风云/剧场），换来零风控风险、可自行维护、线路质量可验证。

## 实现

`tools/gen_live_cctv.py`（仅标准库）：

1. 候选池
   - 官方 CDN：6 个 CDN 主机 × `cdrmldcctv{N}_1`（N=1..17）
   - 移动 IPTV：4 个节点 × `tsfile/live/{NNNN}_1.m3u8?key=txiptv`
   - 挖掘：站点 `综合直播.php`、`优选.txt`、`电视直播.txt`、`Kimentanm.m3u`、`Guovin.m3u`
2. 校验（`probe`）：主清单 →（有则）首个 `#EXT-X-STREAM-INF` 子清单 → 首个分片 Range 取 8KB。
   - 非 m3u8 直链必须内容类型不是 `image/`/`text/`/`json` 且 ≥1KB（避免把台标图当直播流）
   - 分片同样排除 `image/`/`text/` 类型，且 ≥1KB（避免占位分片）
   - 失败项整体重试一轮（CDN 抖动常见）
3. 输出：`CCTV-1..CCTV-17` 规范化命名，官方 CDN（优先级 100）> 移动 IPTV（90）> 挖掘（40），每频道最多 4 条线路；首行写 `更新时间,<时间>`（App 会跳过该行）。

## 部署

- 脚本：`/www/wwwroot/tvbox.moliys.icu/tvbox/live/gen_live_cctv.py`（属主 `www:www`）
- 输出：同目录 `央视.txt`，属主 `www:www`
- 计划任务：宝塔 id `410`「央视直播源刷新」，`23 */6 * * *`（每 6 小时）
  - 命令：`cd /www/wwwroot/tvbox.moliys.icu/tvbox/live && /usr/bin/python3 gen_live_cctv.py --out 央视.txt > /tmp/gen_live_cron.log 2>&1; chown www:www 央视.txt`

注意：宝塔 `AddCrontab` 的 `hour-n` 类型在缺 `minute` 参数时会写出 `None */6 * * *`（无效表达式，任务永不执行）。
必须改用 `modify_crond` 并显式传 `minute`，或添加时就带上 `minute`。

## 验证

- 本地：`python3 tools/gen_live_cctv.py --out /tmp/...` → 逐条端到端校验；2026-09-21 实测 376 候选 / 99 可播 / 16 频道。
- 服务端：`StartTask` 触发 id 410 → 日志 `/tmp/gen_live_cron.log` 显示 376 候选 / 88 可播 / 16 频道，耗时 89.8s。
- 公网：`curl https://tvbox.moliys.icu/tvbox/live/央视.txt` → HTTP 200，`text/plain`，约 8KB，含 `更新时间` 行与 `央视频道,#genre#` 分组。

## 已知限制

- 官方 CDN 仅提供 CCTV-1、CCTV-13；CCTV-2..15、17 依赖移动 IPTV 等公开线路，单点稳定性弱于官方。
- **CCTV-16 暂缺**：穷举 8 条已知候选（含 miguvideo/38.75.136.137/74.91.26.218 等）全部校验失败，不做编号推断以免标错频道；生成器会在后续刷新中自动补上。
- 未覆盖 CGTN、央视风云/剧场等付费或外语频道（生成器显式排除非 CCTV 数字频道）。

## 回滚

- 删除 `tools/gen_live_cctv.py` 与服务器上的脚本/定时任务即可；本改动不触碰 App 代码与既有直播源。
