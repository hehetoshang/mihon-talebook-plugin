# Talebook Mihon Plugin

面向自托管 Talebook 的 Mihon 书源扩展。扩展只展示 Talebook 明确标记为
`media_type = comic` 的书籍；普通 EPUB、PDF 和其他电子书不会进入浏览或搜索结果。

## 兼容性

- Mihon **0.20.0 或更高版本**（Keiyoushi extension API `1.6`，Android 8.0/API 26+）。
- Talebook 必须包含 [talebook/talebook#1012](https://github.com/talebook/talebook/pull/1012)
  的漫画 API，亦即提交 `6d027fea896c54237131a20091a65c077d989087` 或更新版本。
- 截至 2026-09-01，Talebook 最新标签 `v26.08.11` 早于上述合并提交，不能使用本扩展；
  请使用包含该提交的更新版本。
- 当前构建固定使用 Keiyoushi `extensions-source` 提交
  `b0fc6429905a707ea59e107dd40b37f6edd570ee`。

## 安装与配置

1. 从本仓库 Actions 构建产物或 Release 下载 APK，并在 Mihon 中确认信任来源后安装。
2. 打开 Mihon 的“浏览 → 扩展 → Talebook → 设置”。
3. 填写服务器地址、用户名、密码和访问码；未启用访问码时第四项可留空。
4. 点击“测试连接”，成功后进入 Talebook 书源浏览或搜索。

未配置仓库专用签名密钥时，Actions 产物使用构建机调试证书签名，适合首次安装验证；不同
构建之间可能需要先卸载旧版。正式分发前应在 CI 中配置并妥善备份固定签名密钥，且绝不
把密钥提交到仓库。

服务器地址默认使用 HTTPS。只有确定网络边界可信时才应配置明文 HTTP；扩展不会关闭
TLS 校验，也不会接受凭据请求重定向。若 Talebook 为登录或访问码启用了交互式验证码，
当前 Mihon 扩展无法完成验证码，请为专用账号关闭对应验证码或改用无需交互验证的部署。

密码和访问码使用 Mihon 扩展的应用私有 `SharedPreferences` 保存，设置界面始终掩码显示，
不会写入日志、异常或请求 URL。Android/Mihon 当前没有向书源扩展提供硬件加密的统一凭据
存储 API，因此建议使用仅具备阅读权限的专用 Talebook 账号，并保护好设备本身。

## 行为映射

- Browse：`/api/library`；Latest：`/api/recent`；Search：`/api/search`。
- 所有列表均以 `media_type == "comic"` 精确过滤，并跨 Talebook 分页继续扫描，避免某页
  只有电子书时错误终止。
- 每本 Talebook 漫画对应一个 Mihon 漫画条目和一个“完整漫画”章节。
- 打开章节时读取 `/api/book/:id/comic/pages` contract v1，按 manifest 的页序返回带签名的
  图片 URL；自然排序、容器校验、路径隐藏和页面读取限制由 Talebook 漫画 API 负责。
- 登录会话过期后自动重新提交访问码和账号密码一次；错误凭据、无权限、损坏容器、超时、
  非 JSON 响应和不兼容 API 都会返回不含凭据或服务端路径的可理解错误。

当前 Talebook 契约没有卷册/章节层级，所以扩展不会根据文件名猜测章节；若服务器未来
提供稳定的章节 API，可在保持现有漫画 URL 的前提下扩展映射。

## 构建与验证

需要 JDK 17、Android SDK 以及网络访问：

```bash
./scripts/build.sh
```

脚本在临时目录检出固定版本的 Keiyoushi 工程，将 `src/all/talebook` 注入其标准目录，
执行 `testDebugUnitTest`、`lintRelease` 和 `assembleRelease`，再把 APK/JAR 复制到
`artifacts/`。临时目录退出时会自动删除。

只运行指定任务也可以先把本模块复制到相同版本的 `extensions-source/src/all/talebook`，
然后执行：

```bash
./gradlew :src:all:talebook:testDebugUnitTest
./gradlew :src:all:talebook:lintRelease
./gradlew :src:all:talebook:assembleRelease
```

## 许可证

本仓库原创代码采用 [MIT License](LICENSE)。构建时下载但不复制进本仓库的 Keiyoushi
`extensions-source` 使用 Apache-2.0；详情和固定版本见 [UPSTREAM.md](UPSTREAM.md)。
本项目与 Mihon、Keiyoushi 或 Talebook 官方均无隶属关系。
