# NES APK Template

将单个 FC / NES 游戏打包成独立 Android 应用，启动后直接进入游戏。

基于 Kotlin、LibretroDroid 和 FCEUmm，支持触屏、手柄、存档、变速和金手指。为游戏准备 ROM 和配置文件，即可构建 APK。

## 功能

- 每个游戏独立设置应用名称、包名和版本，支持多个游戏共存。
- 支持触屏按键和实体手柄，连接手柄后自动隐藏触屏区。
- 支持游戏进度保存和手动即时存档。
- 支持 1～4 倍速度，通过菜单或手柄肩键调整。
- 支持 VCT、Game Genie 和分组 CHT，可内置预设，也可在应用内添加和导入。

支持 Android 6.0 及以上版本，默认包含 ARM64 和 x86_64 两种架构。

## 快速开始

构建环境支持 Windows x64、Linux x64，以及 Intel / Apple Silicon Mac。ROM 由使用者自行准备。

### 1. 准备环境

Windows 在仓库根目录运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/setup-toolchain.ps1
```

macOS / Linux 需要 Bash、`curl`、`tar`、`unzip`、`iconv` 和 `jq`，在仓库根目录运行：

```bash
./tools/setup-toolchain.sh
```

首次运行会下载工具并安装到 `.toolchains/`，SDK 安装时按提示确认许可证。后续运行复用已有工具；添加 `-CheckOnly` 可检查环境。

Windows 安装项目内的 PowerShell 7。Linux / macOS 的构建和打包直接在终端执行。

### 2. 添加游戏

在 `work/` 中创建长期保留的个人游戏目录；该目录已被 Git 忽略，需自行备份：

```text
work/
└─ my-game/
   ├─ game.properties
   ├─ game.nes
   ├─ game.cht          # 可选
   └─ icon.png          # 可选
```

在 `game.properties` 中填写：

```properties
id=my_game
versionCode=1
versionName=1.0
```

每个目录包含一个 `.nes`、一份配置和可选的一个 `.cht`。游戏文件可保留原名，支持中文和空格。

如需自定义桌面图标，放入名为 `icon.png` 的正方形 PNG，推荐 512 × 512 像素，支持透明背景。省略该文件时使用默认手柄图标。

### 3. 构建 APK

Windows

```powershell
./pack.ps1 -Game work/my-game
```

Linux / macOS

```bash
bash ./pack.sh -Game work/my-game
```

两条命令生成同样命名的 APK。

```text
outputs/my_game/game-v1.0-c1-debug.apk
```

将 APK 安装到 Android 设备或模拟器即可运行。

## 游戏配置

```properties
id=my_game
appName=我的游戏
applicationId=com.example.my_game
versionCode=1
versionName=1.0
```

| 字段 | 说明 |
|---|---|
| `id` | 必填，游戏标识，也是输出目录名 |
| `appName` | 应用名称，默认取 ROM 文件名，不含扩展名 |
| `applicationId` | 包名，默认为 `com.nes.game.<id>` |
| `versionCode` | 必填，正整数，更新应用时递增 |
| `versionName` | 必填，显示版本号，用于 APK 文件命名 |
| `cheatsRomSha256` | 可选，指定配套 ROM 的 SHA-256 |

`id` 以小写字母开头，使用小写字母、数字和下划线。

不同游戏使用各自的目录、ID 和包名。更新已有应用时，保持包名与签名一致，并递增 `versionCode`。目录名、ROM 文件名和应用名称可以独立修改。

项目使用 Android 调试签名。需要跨机器更新同一个应用时，应保留原签名密钥。

配置格式及完整规则见 [打包规则](docs/packaging.md)。

## 常用打包操作

以下示例使用 Windows 入口。Linux / macOS 将 `./pack.ps1` 换为 `bash ./pack.sh`，参数相同。

```powershell
# 检查游戏目录和配置
./pack.ps1 -Game work/my-game -CheckOnly

# 构建并运行单元测试
./pack.ps1 -Game work/my-game -Test

# 覆盖已有的同名 APK
./pack.ps1 -Game work/my-game -Overwrite
```

同名 APK 已存在时，需要显式指定 `-Overwrite`。打包失败会保留原文件。

`-CheckOnly` 检查输入文件和配置。CHT 代码语法与 PNG 图片内容在实际打包时校验。包含 CHT 的游戏会自动运行单元测试。

## 在 IDE 中开发

使用 IntelliJ IDEA 或 Android Studio 打开项目：

1. 运行工具准备程序。
2. 在 `local.properties` 中添加游戏目录：

   ```properties
   gameDir=work/my-game
   ```

3. Gradle JDK 选择 `GRADLE_LOCAL_JAVA_HOME`，项目 Java SDK 选择 `.toolchains/jdk`。
4. Android SDK 使用项目内 `.toolchains/android-sdk`，然后同步 Gradle。

直接通过 Gradle 构建：

```powershell
./gradlew.bat -PgameDir=work/my-game :app:assembleDebug
```

macOS / Linux 使用 `./gradlew`。两个入口均选择项目内 JDK 和 Android SDK。

`-PgameDir` 优先于 `local.properties` 中的选择。Gradle 输出位于 `app/build/outputs/apk/debug/`。使用对应平台的打包入口按游戏配置命名和交付 APK。

## 金手指与存档

将配套 `.cht` 放入游戏目录即可内置到应用。预设默认关闭，启用状态和编辑记录按 ROM 保存。

代码格式、分组和预设管理见 [金手指设计](docs/cheats.md)，ROM 摘要绑定见 [打包规则](docs/packaging.md)。

进入后台或打开菜单时保存游戏进度；即时状态通过菜单手动保存和读取。不同 ROM 内容使用独立存档，卸载应用会删除本地数据。

## 构建检查

Windows 在项目内 PowerShell 中运行：

```powershell
./tests/pack-checks.ps1
./gradlew.bat -PgameDir=work/my-game :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Linux / macOS 运行：

```bash
bash tests/pack-checks.sh
./gradlew -PgameDir=work/my-game :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

设备端的启动、输入、存档和金手指测试见 [验证指南](docs/verification.md)。

## 项目文档

- [架构与设计](docs/architecture.md)：模块、内核、输入和存储。
- [打包规则](docs/packaging.md)：游戏配置、应用身份和 APK 输出。
- [金手指设计](docs/cheats.md)：代码格式、预设管理和内核适配。
- [验证指南](docs/verification.md)：构建检查和设备测试。
- [依赖说明](DEPENDENCIES.md)：组件来源与本地改动。

## 开源协议

项目采用 [GPL-3.0-or-later](LICENSE)，第三方组件许可见 [第三方声明](THIRD_PARTY_NOTICES.md)。游戏 ROM 的授权单独处理。
