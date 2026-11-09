# 验证指南

环境准备见 [使用说明](../README.md)，输入和交付约束见 [打包规则](packaging.md)。命令从仓库根目录、项目内 PowerShell 中执行；设备标识和待测游戏目录由执行者提供。

以下环境检查命令使用 Windows 入口；macOS / Linux 用 `./tools/setup-toolchain.sh -CheckOnly`、`./.toolchains/pwsh/pwsh` 和 `./gradlew` 替换对应命令。

工具环境检查：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/setup-toolchain.ps1 -CheckOnly
./.toolchains/pwsh/pwsh.exe -NoProfile -File tests/toolchain-checks.ps1
./gradlew.bat --version
```

确认 JDK、PowerShell 和 SDK 组件版本符合项目配置，Gradle 的 Launcher JVM 和 Daemon JVM 指向项目 JDK。重复运行准备入口后，本地游戏选择应保持不变；环境检查和打包结束后，调用终端的 JAVA_HOME、SDK 和 PATH 应恢复。

## 构建与输入检查

安装 Node.js 后，可运行 `./tests/build-checks.ps1` 生成自编测试 ROM 和独立配置，依次执行工具链检查、打包输入检查、单元测试、APK 打包和 lint。GitHub Actions 使用同一入口。

Gradle 发行包下载失败时，构建检查从同一官方发布资产预取文件，按 Wrapper 配置中的 SHA-256 核验后放入标准缓存，再由原 Wrapper 继续启动。

```powershell
./tests/pack-checks.ps1
./pack.ps1 -Game <game-directory> -CheckOnly
./gradlew.bat -PgameDir=<game-directory> :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

将 `<game-directory>` 替换为待测游戏目录。根目录 `gradle.properties` 默认包含 arm64-v8a 和 x86_64；仅验证模拟器时可用 `-PtargetAbis=x86_64`，仅验证 ARM64 手机时可用 `-PtargetAbis=arm64-v8a`。

验证 IDE 构建时，先运行工具准备入口，在 `local.properties` 显式配置 `gameDir`，将 Gradle JDK 选择为 `GRADLE_LOCAL_JAVA_HOME`，SDK 冲突时选择项目 SDK；重新同步 Gradle，再执行“构建项目”。同步与构建均应成功，构建日志中的 JDK、SDK 和原生工具路径均应位于本项目 `.toolchains/`。另用 `-PgameDir` 指定其他目录，确认命令行选择优先于本地配置。

`pack-checks.ps1` 自行生成输入夹具，检查目录数量、扩展名、名称配对、配置与编码、摘要、身份、输出冲突及路径处理。检查输出中的失败和跳过项，确认符号链接用例是否执行。

JVM 测试覆盖输入、触屏布局、设备分类、倍率按键、FPS、存储和金手指逻辑。它们不替代设备端启动、音视频与输入检查。

## 打包交付回归

在独立测试目录和测试应用身份下执行，保留已有产物作为比较基准：

1. 使用合法 ROM/CHT 构建，通过 `pack.ps1` 交付前核验包名、版本及包内 ROM/CHT 摘要；需要时使用 SDK 的 APK 分析与签名工具复核元数据、ABI 和签名。
2. 不加 `-Overwrite` 再次交付到同一路径，应拒绝且旧产物摘要不变；显式覆盖时应完成核验后替换。
3. 将 CHT 改为非法代码，实际打包应失败且不改变已有交付 APK。`-CheckOnly` 不能替代应用解析器的代码语法校验。
4. 在相同 CHT 路径修改内容后重建，确认测试重新读取输入，而非复用旧测试结果。
5. 从带 CHT 的游戏切换到不带 CHT 的游戏，检查 APK 没有残留 `game.cht`。空文件和纯注释 CHT 应可打包。
6. 构建不同 ID 与包名的两个测试应用，核对可共存；重命名目录或 ROM 文件后，应用身份应保持稳定。
7. 使用中文、空格及 Windows 非法文件名字符作为显示名，核对应用名称保留原值，输出文件名按规则清理。
8. 检查打包配置快照与当前输入不一致时构建被拒绝，不能交付混用身份或资源的 APK。

## ROM 启动抽样

在明确的测试设备上安装对应 ABI 的 APK，启动后检查可见游戏输出，用 Start、方向和 A/B 等基础输入确认响应。每个待支持的 ROM 版本分别检查，无需以完整通关作为 smoke test 条件。

需验证声音时，应启用宿主音频并实际试听。Android Gamepad 来源的注入事件可以检查事件路由，实体蓝牙或 USB 手柄的枚举、键码、斜向、同按、断连释放和延迟需用实际手柄另测。

## 桌面返回与存储

先手动启动测试应用，再执行桌面返回回归。`Device` 为必填参数；测试包应使用独立身份并显式指定 `Package`：

```powershell
./tests/launcher-resume.ps1 -Device <device-id> -Package <application-id>
```

脚本连续执行 Home 与桌面入口返回，检查 Activity 实例和 PID 是否保持一致。该检查验证现有进程中的会话复用，不覆盖系统回收后的恢复。

存储测试使用独立测试数据：保存 SaveRAM 后重启并核对进度；手动保存即时状态、改变游戏状态后读取，核对恢复。更换 ROM 内容后应隔离存储，损坏状态及内核指纹不匹配的即时状态应被拒绝。涉及既有游戏数据时，先备份并在结束后核对恢复内容；备份及摘要留在本地证据中。

## 手柄、变速和 FPS

检查实体手柄连接后隐藏触屏区、断开后恢复；普通键盘不应触发布局切换。切换前后的按键状态不能残留，游戏会话不应重建。

菜单依次选择 1～4 倍并重新打开核对选中值；L1/R1 单次按下各变化一档，长按和重复 DOWN 不应连续触发，达到上下限后保持。FPS 检查左上角显示、不拦截触摸、暂停后重置采样；验证模拟速度时需另行测量，不能用该渲染回调频率直接代替。

## 原生金手指回归

先构建目标 ABI 的核心，再运行探针；SDK 使用项目工具目录，设备标识由参数提供：

```powershell
./tests/native/run-cheats.ps1 -Device <device-id> -Abi x86_64
```

探针检查明确 RAM 指令、后续帧重写、清空后停止、Game Genie 生效与移除、混合 token 类型隔离、Mapper 246 SRAM 映射和非 RAM 区域保护。

## 应用金手指链路

使用自编写 ROM 可将 UI 验证与商业游戏数据隔离。先在本地测试游戏目录写入合法且使用独立身份的 `game.properties`，再生成 ROM：

```powershell
node ./tests/native/make-cheat-rom.cjs <test-game-directory>/probe.nes
./pack.ps1 -Game <test-game-directory> -Test
```

该 Mapper 246 ROM 用 CPU RAM `0x6800` 控制背景调色板。以下操作检查解析、UI、持久化及核心注入的整条链路：

1. 无代码时应显示蓝色；添加 VCT `6800-01-16`，确认默认停用，启用后应变红。
2. 停止并重新启动测试应用，启用状态应恢复；停用后重置游戏，应回到蓝色。
3. 添加并启用 Game Genie `EAZTLA`，应变黄；停用后应恢复蓝色。
4. 检查添加、停用、删除、多行导入，以及错误输入整体拒绝；导入默认停用。
5. 随包加入分组 CHT，检查每组选项共用开关、新预设默认关闭、已有记录优先、删除后重启不复活，以及显式恢复补齐缺项。

截图可对照蓝、红、黄背景的预期色相，不应把特定渲染环境的单个 RGB 值当成通用阈值。通过 UI 自动化读取弹窗前可打开暂停菜单，避免持续 FPS 更新影响空闲判断；软键盘出现后重新读取控件位置，避免复用旧坐标。

游戏专用代码另用匹配版本 ROM 验证：同时对照游戏界面、即时状态中的目标地址和相邻字节；多字节代码逐字节检查。停用后只要求停止强制写入，不要求回滚到预设旧值。
