# 沫离TV（Moliys TV）

本仓库 fork 自 [fish2018/webhtv](https://github.com/fish2018/webhtv)（WebHomeTV），
后者基于 [FongMi/TV](https://github.com/FongMi/TV) 生态二次开发。

## 许可证

本项目继续以 **GPL-3.0** 发布，完整许可证见 [LICENSE](LICENSE)。
分发本项目的修改版时，必须同时提供完整源码。

## 本 fork 的二次修改

- 应用名改为「沫离TV」，`applicationId` 改为 `com.moliys.tv`
- 首次启动默认点播配置指向 `https://tvbox.moliys.icu/webhome/config.json`
- 版本更新源改指本仓库，不再请求上游
- 新增免签名依赖的 debug 构建工作流 `.github/workflows/moliys-debug.yml`
- debug 包使用 Android 默认调试签名，无需配置签名密钥
