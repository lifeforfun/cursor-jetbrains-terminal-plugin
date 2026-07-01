# Cursor CLI Terminal

JetBrains IDE 插件，在底部工具窗内集成 [cursor-agent](https://cursor.com) CLI，把 Cursor Agent 对话搬进 IDE 终端，同时保留原生终端的输入与滚动行为。

## 功能概览

### 1. 会话管理（开启 / 恢复 / 新对话）

- 打开 **Cursor CLI Terminal** 工具窗后，自动在项目目录下启动 `cursor-agent`。
- 支持 **恢复上次对话**：从 `~/.cursor/chats/<项目路径 MD5>/` 解析最近会话，并以 `--resume <chatId>` 启动。
- 点击工具栏 **「开启会话」**：
  - 无活跃会话时：启动或恢复 cursor-agent；
  - 已有活跃会话时：确认后结束当前对话并开启全新会话。
- 会话 ID 通过多层策略解析（优先级从高到低）：
  1. 显式指定的 preferred ID
  2. 内存中的 active 会话
  3. 持久化文件 `.plugin-bound-session`
  4. 30 秒 TTL 内存缓存
  5. 扫描 `meta.json`，取 `updatedAtMs` 最大且有对话记录的会话
- 终端启动后会在 1s / 3s / 8s 补录新产生的 chatId，避免会话绑定滞后。

### 2. 路径注入（@ 引用）

- 点击工具栏 **「注入路径」**，将当前激活编辑器标签页的路径以 `@` 语法注入终端输入区。
- 若编辑器有选区，自动附带行号范围，例如 `@src/main.kt:10-25`。
- 路径相对于项目根目录；空格会自动转义。
- 若终端尚未就绪，会自动尝试恢复会话后再注入。

### 3. 图片粘贴

- 剪贴板为 **图片** 时，`Ctrl+V`（macOS 为 `Cmd+V`）会向 cursor-agent 发送 `0x16`（SYN），触发 Agent 的图片粘贴能力。
- 剪贴板为 **纯文本** 时不拦截，走终端默认粘贴逻辑。

### 4. 终端交互增强

- **URL 纯文本展示**：读路径剥离 OSC8 超链接控制序列，去掉链式下划线，减少链接后全文带下划线；启动时注入 `NO_HYPERLINK=1` 提示 cursor-agent 少发超链接序列。
- **Shift 扩选**（Jedi 面板）：先点选起点，滚动后 `Shift+点击` 扩展选区。

## 设计原则

插件刻意保持「轻介入」：

| 会做 | 不会做 |
|------|--------|
| 显式按钮触发路径注入 | 拦截 Enter 键 |
| 剪贴板含图片时接管粘贴快捷键 | 修改终端滚动行为 |
| 自动启动 / 恢复 cursor-agent 会话 | 全局监听编辑器或挂钩输入 |

三个功能模块（会话、路径注入、图片粘贴）彼此独立，仅通过工具栏按钮或快捷键显式触发。

## 使用方式

1. 安装插件（见下方构建说明）。
2. 确保本机已安装 `cursor-agent`，或在环境变量中指定路径：
   ```bash
   export CURSOR_AGENT_PATH=/path/to/cursor-agent
   ```
3. 在 IDE 底部打开 **Cursor CLI Terminal** 工具窗。
4. 等待自动开启会话，或点击 **「开启会话」** 手动控制。
5. 在编辑器中打开目标文件，点击 **「注入路径」** 将 `@` 引用送入终端。
6. 截图或复制图片后，在终端焦点处 `Ctrl+V` / `Cmd+V` 粘贴给 Agent。

## 环境要求

- JetBrains IDE（基于 IntelliJ Platform，`sinceBuild` 233+）
- 依赖官方 **Terminal** 插件（`org.jetbrains.plugins.terminal`）
- 本机可用的 `cursor-agent` CLI
- 构建目标：GoLand 2025.1（`build.gradle.kts` 中配置，可调整）

## 构建与安装

```bash
# 编译并打包插件 zip
./gradlew buildPlugin

# 产物路径
# build/distributions/cursor-cli-terminal-plugin-<version>.zip
```

在 IDE 中选择 **Settings → Plugins → ⚙ → Install Plugin from Disk…**，选中上述 zip 安装。

本地开发调试：

```bash
./gradlew runIde
```

## 项目结构

```
src/main/kotlin/com/github/cursorterm/
├── CursorTerminalToolWindowFactory.kt   # 工具窗入口
├── CursorAgentTerminalController.kt     # 功能编排与工具栏
├── TerminalLauncher.kt                  # cursor-agent 启动命令构建
├── TerminalBootstrap.kt                 # 终端 Widget 兼容层
├── CursorAgentSessionStore.kt           # 会话 ID 解析与持久化
├── EditorContextCollector.kt            # 编辑器 @ 路径采集
├── terminal/                            # Block / Classic 终端访问层
└── feature/
    ├── SessionFeature.kt                # 会话生命周期
    ├── PathInjectFeature.kt             # 路径注入
    ├── ImagePasteFeature.kt             # 图片粘贴
    ├── TerminalInteractionFeature.kt    # 交互增强编排
    └── TerminalPlainTextFeature.kt      # URL 纯文本输出过滤
```

## 会话存储说明

cursor-agent 本地会话目录结构：

```
~/.cursor/chats/<md5(项目路径)>/<chatId>/meta.json
```

插件额外在项目 chats 目录下写入 `.plugin-bound-session`，用于跨重启记住绑定会话。

## 版本

当前版本：**3.2.0**（见 `build.gradle.kts` / `plugin.xml`）

### 变更记录

#### 3.2.0

- **Block 终端**：直接 `exec cursor-agent`（不经 shell 包装），`CURSOR_AGENT_PATH` / IDE PATH 解析，可移植启动。
- **URL 纯文本展示**：Tty 读路径剥离 OSC8 与链式下划线；会话启动注入 `NO_HYPERLINK=1`。
- **Shift 扩选**：Jedi 面板支持滚动后 Shift 扩展选区。
- **模块拆分**：`terminal/` 统一 Block/Classic 访问；`TerminalInteractionFeature` 独立安装各子功能。
- 未纳入：跨行 URL 点击、悬停/点击拦截（IDE 内置超链接无法可靠关闭，已放弃）。

#### 3.0.2

- 会话 / 路径注入 / 图片粘贴模块化；工具窗图标。
