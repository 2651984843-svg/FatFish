# APK 下载与 Release 上传指引

本仓库**不提交 APK**（避免 git 体积爆炸），所有安装包统一通过 **GitHub Releases** 分发并永久留档。

## 一、APK 版本清单

> 留档规则：每个版本永久保留，绝不删除。历史版本全部上传至 Releases 的 assets。

### 手表端 FatFishWatch（约 116MB / 个）

| 版本 | 文件名 | 说明 |
|------|--------|------|
| v15（最新） | FatFishWatch_v15.apk | 远程拉取配置（对接手机端） |
| v14 | FatFishWatch_v14.apk | 修复大小调节主界面不生效 |
| v13 | FatFishWatch_v13.apk | 大肥鱼大小无极调节 |
| v12 | FatFishWatch_v12.apk | 修复旋转多余动画 |
| v11 | FatFishWatch_v11.apk | 随重力旋转开关 |
| v10 | FatFishWatch_v10.apk | 返回按钮 |
| v9 | FatFishWatch_v9.apk | 6 套人格 + 独立记忆 |
| v8 | FatFishWatch_v8.apk | 语音/键盘输入二选一 |
| v7 | FatFishWatch_v7.apk | Agent function calling |
| v6 | FatFishWatch_v6.apk | 联网搜索 |
| v5 | FatFishWatch_v5.apk | 时间 + 天气 |
| v4 | FatFishWatch_v4.apk | 完整设置页 + 模型列表 |
| v3 | FatFishWatch_v3.apk | 第三方中转站支持 |

### 手机端 FatFishPhone（约 3.4MB）

| 版本 | 文件名 | 说明 |
|------|--------|------|
| v1 | FatFishPhone_v1.apk | 配置推送服务（HTTP :8765） |

## 二、上传指引

在 GitHub 仓库页面按以下步骤发布（每个版本一个 Release）：

1. 进入 **Releases → Draft a new release**。
2. **Tag** 填版本号，如 `watch-v15`、`phone-v1`（建议语义化）。
3. **Title** 填 `FatFishWatch v15` 等。
4. 点击 **Attach binaries** 上传对应 APK（可一次上传多个历史版本资产）。
5. 在 **Describe this release** 里粘贴对应版本变更（见 [CHANGELOG.md](../CHANGELOG.md)）。
6. 点击 **Publish release**。

> 也可用 GitHub CLI 批量上传：
>
> ```bash
> gh release create watch-v15 FatFishWatch_v15.apk \
>   --title "FatFishWatch v15" \
>   --notes "远程拉取配置（对接手机端）"
> ```

## 三、下载方式

发布后，用户可从仓库 **Releases** 页下载对应 APK，直接 `adb install` 侧载到手表或手机。
