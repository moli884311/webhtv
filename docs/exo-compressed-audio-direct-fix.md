# Exo 播放卡顿、播放中途丢声：关闭「压缩音频直通」实验路径

## 现象（用户 2026-09-21 报告，1.0.64 真机）

- Exo 内核播放一直卡顿，播放一会儿后没有声音。
- 同一视频换 IJK 内核播放正常（用户 11:31 确认）；MPV 未测。

结论：问题在 Exo 独有的音频输出路径上，不在片源、网络或解码器本身。

## 涉及的现有实现（改动前的行为）

1. `app/src/main/java/com/fongmi/android/tv/player/exo/ExoUtil.java`
   构建 `DefaultTrackSelector` 时**无条件**写死 `AUDIO_OFFLOAD_MODE_ENABLED`。
   Media3 的默认值是 `AUDIO_OFFLOAD_MODE_DISABLED`，且该处没有设置
   `setIsGaplessSupportRequired` / `setIsSpeedChangeSupportRequired`，等于允许「设备不支持变速也走 offload」。
2. `app/src/main/java/com/fongmi/android/tv/player/exo/ExoCompressedAudioDirectPolicy.java`
   在标准 offload 不受支持、而平台 `AudioManager.getDirectPlaybackSupported()` /
   `AudioTrack.isDirectPlaybackSupported()` 自称支持时，把 AAC(-LC/HE/XHE/ELD)、MP3 等压缩格式
   直接标成 `FORMAT_SUPPORTED_DIRECTLY`，然后**绕过 Media3 的 AudioSink**，用自建
   `AudioTrack`（`setOffloadedPlayback(false)`、`setSessionId(0)`、256 KB 缓冲）直接写压缩帧。
   源码注释本身写明这是给「vivo 的压缩输出」写的兼容分支。
   - 该分支的触发条件只看「平台是否自称支持 direct playback」+「编码是否属于压缩帧」，
     **不看厂商**。AOSP 的 `isDirectPlaybackSupported` 对 AAC/MP3 在大量机型上返回 true，
     所以这个厂商兼容分支会在非目标机型上被普遍启用。
   - 写失败或初始化失败时走 `ExoPlayerEngine.retryAudioOutputWithPcm()`，会**从当前位置整体重开**当前条目。
3. `Setting.isAdblock()`（`adblock`，默认开）在 Exo 侧只对含 `#EXT-X-ENDLIST` 的 VOD 播放列表做广告片段过滤，
   不改 `#EXT-X-MEDIA-SEQUENCE`。未证实与本问题相关，本次不动。

## 证据等级

- 代码事实（A 级）：上面 1、2 两条均为实际读到的代码，行号见下表改动点。
- 因果推断（B 级）：厂商 direct 分支在非目标机型上导致卡顿/丢声属于推断，**没有该机型日志**。
- 反证线索：IJK 内核不经过 Media3 的 AudioSink/offload，用户实测 IJK 正常，与「问题出在 Exo 音频输出路径」一致。

## 决策

对比三个方案：

| 方案 | 结论 |
|---|---|
| 不改 | 用户正常观影受阻，不可接受 |
| 直接删除厂商 direct 代码 | 会丢掉厂商 HAL 的兼容能力，且改动面大、不可回退 |
| **把该路径改为默认关闭的开关** | 采纳：默认回到 Media3 默认行为（解码成 PCM 输出），与 IJK 表现一致；需要时可用同一个开关开启 |

改动点：

- `ExoPerformanceSetting`：新增 `perf_exo_audio_direct`（默认 `false`）与 `isAudioDirect()` / `putAudioDirect()`。
- `ExoUtil.buildTrackSelector`：offload 模式改为「开关开 → ENABLED，否则 DISABLED（Media3 默认）」。
- `ExoCompressedAudioDirectPolicy.getFormatSupport`：开关关闭时直接返回标准结果，不再把压缩格式标成
  `FORMAT_SUPPORTED_DIRECTLY`，`vendorDirectConfigs` 不会被写入，后续 `getAudioTrackBuilder` 修改与
  自建 AudioTrack 分支自然不再进入。

保留项：`音频直通`（`perf_exo_audio_pass_through`，默认开）语义不变 —— 直通是「把原始码流交给下游设备」，
与本次关闭的 offload/direct 输出是两回事，AVR 用户不受影响。

## 风险与回退

- 风险：个别机型原本依赖厂商 direct 分支才能出声，关闭后会退回 PCM 解码。
  这属于「回到 Media3 默认」，风险可控；出问题可用 `putAudioDirect(true)` 恢复。
- 本次不给该开关加设置界面入口（避免扩大改动面），如后续需要再按键盘/播放性能页惯例补。

## 验证

- 本地 `javac`：`ExoPerformanceSetting.java`、`ExoCompressedAudioDirectPolicy.java` 用仓库内
  `third_party/maven/androidx/media3/*` 的 classes.jar + `android.jar` 完整编译通过，0 错误；
  `ExoUtil.java` 仅剩 `androidx.annotation`、生成的 `BuildConfig` 三类本地缺依赖符号，与本次改动无关。
- 真机：Exo 播放同一视频不再卡顿、不再中途丢声（用户复测）。

## 改动文件

- `app/src/main/java/com/fongmi/android/tv/setting/ExoPerformanceSetting.java`
- `app/src/main/java/com/fongmi/android/tv/player/exo/ExoUtil.java:322`
- `app/src/main/java/com/fongmi/android/tv/player/exo/ExoCompressedAudioDirectPolicy.java:84`
