# Desktop Panel

这是一个纯 Java Swing 面板，避免引入 Electron/Node 作为 MVP 基座。

## 运行

1. 安装 JDK 17+
2. 用 IDE 打开 `desktop/`
3. 运行 `com.debugtools.desktop.DesktopMain`

## 功能

- 连接 Android probe
- 拉取 View 树
- 默认展示被调试 app 的 HTTP API 流量
- 选中流量记录后编辑并启用 mock
- 管理和关闭 mock 规则
