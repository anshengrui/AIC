# EasyAccess 初赛提交包工作区

本目录只保存可以公开或正式提交的材料。所有内容在进入此目录前必须完成隐私和密钥检查。

建议最终结构：

```text
submission/
|-- 01-application/       # 报名表、团队信息、承诺书（按官方要求处理）
|-- 02-presentation/      # PPT 源文件和 PDF 备份
|-- 03-demo-video/        # 3 分钟视频、字幕和配音稿
|-- 04-software-manual/   # 软件说明书/操作手册
|-- 05-technical/         # 核心算法说明、架构图、接口说明
|-- 06-test-report/       # 数据说明、指标、对比实验、失败案例
|-- 07-compliance/        # 数据授权、隐私、安全与第三方模型声明
|-- 08-release/           # APK/下载说明、源码提交哈希、现场启动说明
`-- README.md
```

## Git 与大文件

- PPT、Markdown、PDF 说明和小型图表可以进入 Git。
- APK、视频、完整数据包和云函数 ZIP 建议放 GitHub Release 或团队网盘，在此目录记录下载地址和 SHA-256。
- 官方签字文件、身份证明、联系方式和未脱敏承诺书不要放公开仓库。
- 根目录规格书 PDF 和参赛承诺书当前被 `.gitignore` 排除，由负责人私下分发。

## 推荐命名

```text
EasyAccess_软件说明书_v1.0.pdf
EasyAccess_核心算法说明_v1.0.pdf
EasyAccess_测试报告_v1.0.pdf
EasyAccess_初赛答辩_v1.0.pptx
EasyAccess_演示视频_v1.0.mp4
EasyAccess_Android_v1.0.apk
EasyAccess_现场启动说明_v1.0.pdf
```

## 每次打包前

1. 记录源码提交哈希：`git rev-parse HEAD`。
2. 运行 `docs/TEAM_HANDOFF.md` 中的基线测试。
3. 对照 `docs/PRELIMINARY_SUBMISSION_CHECKLIST.md` 逐项勾选。
4. 在一台未配置开发环境的电脑或手机上验证说明书和安装包。
5. 将提交包复制到 U 盘和网盘，保留离线 Mock 与演示视频。
