# MIB2Q CarPlay RGI Improvements

> [!WARNING]
> 本项目会修改车机固件文件及仪表显示行为。部署前请完整备份原文件，相关风险由使用者自行承担。
>
> This project modifies infotainment firmware files and cluster display behavior. Back up the original files before deployment. Use at your own risk.

---

## 中文说明

### 项目来源

本项目源自 [luka-dev/mib2q-carplay-rgi](https://github.com/luka-dev/mib2q-carplay-rgi)。原项目已经实现 CarPlay 导航路径引导在 MHI2Q 虚拟座舱中的显示；本项目主要针对实车路试中发现的问题进行调整和完善。

### 原项目路试中发现的问题

1. **首个路径引导箭头无法正常加载**  
   首次开启 CarPlay 导航后，仪表右侧路径引导窗口有时已经被唤起，但箭头没有被正常绘制，箭头区域表现为透明或空白。切换一次仪表布局后，箭头才能恢复正常显示。

2. **未根据实际掉头方向区分左右掉头**  
   原项目的 Java 映射没有根据当前 maneuver 的有符号出口角度输出左、右掉头方向；当方向未明确传递时，renderer 会根据 `driving_side` 选择固定方向。在测试车辆上，掉头箭头表现为默认向右。

3. **真实转向箭头提示距离过早**  
   原项目将普通路段的真实箭头显示阈值设为 **1.5 km**，高速路段设为 **3 km**；同时，只要当前 route step 长度超过 **1.5 km**，就会将其按高速路段处理。在中国城市高架、快速路及长距离城市道路中，route step 超过 1.5 km 较为常见，因此容易使真实转向箭头提前数公里出现并长时间停留。

4. **CarPlay 导航结束后原车导航无法接管路径引导窗口**  
   停止 CarPlay 导航或断开 CarPlay 连接后，原车导航无法重新取得仪表右侧路径引导箭头小窗口的显示权限，通常需要重启车机后才能恢复。

### 本项目主要变动

1. **重构首个箭头的加载流程**  
   renderer 改为按需启动。窗口切入仪表前，先完成画面预加载、`READY` 和 `FRAME_READY` 握手，确认首帧已经准备完成后再开启 GFX 并切入箭头 Context，避免透明或未初始化的窗口被提前显示。

2. **增加左右掉头方向判别**  
   掉头方向优先根据 CarPlay/iAP2 提供的有符号出口角度判断：负值映射为左掉头，正值映射为右掉头；当角度缺失、无效或为 `0` 时，默认回退为左掉头，不再仅依赖 `driving_side` 判断掉头方向。

3. **根据中国区道路环境调整箭头显示距离**  
   普通或城市路段在距离下一动作 **350 m** 内显示真实 maneuver；当相邻 route step 长度超过 **2 km** 时，采用长路段模式，在 **1000 m** 内显示真实 maneuver；距离更远时显示直行箭头。

4. **调整 CarPlay 退出后的原车导航接管流程**  
   CarPlay 导航停止或 CarPlay 断开时，先清理 CarPlay renderer、TCP、GFX、route-info、KOMO/Context 等显示资源，再释放原车导航的 route-guidance gate。当前修改已能够实现 CarPlay 退出后原车导航的第一次正常接管。

5. **调整 renderer 与 BAP 的状态同步**  
   距离条、接近区状态、call-for-action 闪烁及 custom renderer 更新尽量采用同一组状态和时序，减少仪表文字、HUD 与 MOST 箭头画面之间的显示差异。

### 已修改，但尚待实车验证

1. **零距离首帧启动**  
   该问题出现在本项目此前的测试版本中，并非原项目的固有问题。同一 CarPlay 连接内停止并重新开启导航时，首个有效 primary maneuver 的 `dist_maneuver_m` 可能暂时为 `0`；此前的启动条件会因此阻止 renderer 建立。当前代码允许在本次导航首幅 renderer 画面尚未成功显示时，将完整有效且距离为 `0` 的 primary maneuver 作为当前位置的立即动作处理，并按真实类型显示。该逻辑尚未完成实车验证。

2. **多次重复 CarPlay 导航与原车导航接管**  （失败）
   当前已经能够实现一次正常接管，但此前路试发现：原车导航第一次接管并关闭后，再次开启 CarPlay 导航，随后再次停止 CarPlay 导航或断开 CarPlay，原车导航仍可能无法第二次接管路径引导窗口。当前代码已进一步调整退出清理和 gate 释放顺序，但尚未完成重复循环实车验证。

### 下载与部署

正式发布文件位于 [`release/`](release/)：

- `release/carplay_hook.jar`
- `release/maneuver_render`
- `release/SHA256SUMS`

部署时必须同时替换 `carplay_hook.jar` 与 `maneuver_render`，不要将本项目的 JAR 与其他版本的 renderer 混用。

| 文件 | SHA-256 |
| --- | --- |
| `carplay_hook.jar` | `bd7f04479b269e36c6c1e88d6e52e077135f51fa6016c506895991c9efc6338b` |
| `maneuver_render` | `f86c7a44288d55c352837b3432874cf81836431929e625e7cded42d5664e993e` |

固件配置参考文件位于 [`deploy/`](deploy/)；`build/` 仅用于本地生成的编译产物，不纳入版本控制。

### 从源码构建

#### 前置条件

- **QNX SDP 6.5**：用于交叉编译 ARM32 QNX 的 C hook 和 renderer。推荐准备可通过 SSH 访问的 QNX 6.5 工具链环境。
- **Java 8、`lsd.jar` 与 OSGi 依赖**：用于完整编译 Java patch。`lsd.jar` 需要从目标固件的 `/mnt/app/eso/hmi/lsd/lsd.jxe` 提取并通过 `jxe2jar` 转换。
- **GLFW 3（可选，仅 macOS 本地 renderer 测试）**：`brew install glfw`。

#### 完整编译 `carplay_hook.jar`

与原项目相同的全源码编译入口：

```bash
JAVA_HOME=/path/to/jdk8 \
JXE2JAR_DIR=../jxe2jar \
./build_java.sh
```

也可以直接设置 `LSD_JAR` 和 `OSGI_LIBS`。输出为 `build/carplay_hook.jar`，全部 Java 类以 `-source 1.2 -target 1.2` 编译。

#### 可复现构建正式 JAR

不具备固件 `lsd.jar` 时，可使用仓库内的兼容 stub 和 `build_assets/carplay_hook_base.jar` 重组正式 JAR：

```bash
./build_release.sh jar
```

输出为 `release/carplay_hook.jar`。固定 Build ID、ZIP 条目时间戳和打包属性用于生成稳定的发布文件。

#### 编译 `libcarplay_hook.so`

```bash
QNX_VM=192.168.64.16 \
QNX_USER=root \
QNX_PASSWORD=root \
./compile_hook.sh
```

输出为 `build/libcarplay_hook.so`。日志构建选项：

```bash
LOG=0 ./compile_hook.sh
LOG=1 LOG_RGD_PACKET_RAW=1 ./compile_hook.sh
```

#### 编译 `maneuver_render`

QNX 部署版本：

```bash
QNX_VM=192.168.64.16 \
QNX_USER=root \
QNX_PASSWORD=root \
./compile_render_qnx.sh
```

调试网格版本：

```bash
./compile_render_qnx.sh grid
```

默认输出为 `release/maneuver_render`。Windows 上安装本地 QNX SDP 后，也可运行：

```powershell
.\compile_render_qnx_windows.ps1
```

macOS 本地 renderer 测试：

```bash
brew install glfw
make -C c_render
```

Windows 本地 renderer 测试：

```powershell
.\compile_render_windows.ps1
```

本地测试产物统一写入 `build/`，不会作为正式发布文件提交。

#### 统一入口

```bash
./build_release.sh jar       # 正式 JAR
./build_release.sh renderer  # QNX renderer
./build_release.sh hook      # C hook
./build_release.sh all       # 正式 JAR + renderer
./build_release.sh full      # C hook + 正式 JAR + renderer
./build_release.sh verify    # 校验 release/ 文件
```

### 与原项目不同的部署文件

实际内容发生变化、需要配套替换的部署文件有两个：

- `carplay_hook.jar`：Java 侧箭头状态机、首帧启动、显示阈值、掉头方向、生命周期及原车导航接管逻辑；
- `maneuver_render`：renderer 预加载、首帧确认、显示控制及 Context 恢复逻辑。

`libcarplay_hook.so` 和 `flag_atlas.rgba` 与原项目保持一致。

---

## English

### Origin

This project is derived from [luka-dev/mib2q-carplay-rgi](https://github.com/luka-dev/mib2q-carplay-rgi). The upstream project already provides CarPlay route-guidance rendering for the MHI2Q Virtual Cockpit. This project focuses on improvements based on issues observed during vehicle testing.

### Issues observed in the upstream project

1. **The first maneuver arrow may fail to load**  
   The right-side maneuver window may already be active while the arrow area remains transparent or empty. Changing the cluster layout once is required to recover the arrow.

2. **Left and right U-turns are not determined from the actual signed exit angle**  
   The upstream Java mapping does not output a left/right U-turn direction from the current maneuver's signed exit angle. When no explicit direction is passed, the renderer falls back to a fixed side selected from `driving_side`. The tested vehicle displayed a right U-turn by default.

3. **Real maneuver arrows appear too early**  
   The upstream thresholds are **1.5 km** for normal roads and **3 km** for highways, while any route step longer than **1.5 km** is treated as a highway step. This is not suitable for many Chinese urban elevated roads, expressways, and long city sections.

4. **Stock navigation cannot reclaim the maneuver window after CarPlay guidance ends**  
   After CarPlay guidance stops or CarPlay disconnects, stock navigation may be unable to regain the right-side maneuver window until the head unit is rebooted.

### Main changes in this project

1. **Reworked first-arrow startup**  
   Frame preload and the `READY` / `FRAME_READY` handshake complete before GFX and the maneuver Context are exposed to the cluster.

2. **Added left/right U-turn detection**  
   U-turn direction is determined from the signed exit angle provided by CarPlay/iAP2: negative values map to a left U-turn and positive values map to a right U-turn. When the angle is missing, invalid, or `0`, the code falls back to a left U-turn instead of relying only on `driving_side`.

3. **Adapted maneuver timing for Chinese road conditions**  
   Real maneuvers are shown within **350 m** on normal or city route steps. When the adjacent route step exceeds **2 km**, a long-step mode uses a **1000 m** threshold. A straight arrow is shown while farther away.

4. **Adjusted stock-navigation handoff after CarPlay exits**  
   CarPlay renderer, TCP, GFX, route information, and KOMO/Context resources are cleaned up before the native route-guidance gate is released. One stock-navigation handoff has been achieved without immediately rebooting the head unit.

5. **Aligned renderer and BAP timing**  
   Distance, approach-zone state, call-for-action blinking, and custom-renderer updates share the same state and timing path as far as possible.

### Modified but pending vehicle verification

1. **Zero-distance first-frame startup**  
   This issue was introduced in an earlier test build of this project rather than upstream. While the first renderer frame is still pending, a complete valid primary maneuver with distance `0` may now be rendered immediately using its real type. Vehicle verification is still pending.

2. **Repeated CarPlay guidance and stock-navigation handoff**  （Failure）
   One handoff can currently succeed. Cleanup and gate-release ordering have been adjusted again to support the second and later cycles, but repeated vehicle testing is still pending.

### Download and deployment

Ready-to-use files are stored in [`release/`](release/):

- `release/carplay_hook.jar`
- `release/maneuver_render`
- `release/SHA256SUMS`

Replace `carplay_hook.jar` and `maneuver_render` together.

| File | SHA-256 |
| --- | --- |
| `carplay_hook.jar` | `bd7f04479b269e36c6c1e88d6e52e077135f51fa6016c506895991c9efc6338b` |
| `maneuver_render` | `f86c7a44288d55c352837b3432874cf81836431929e625e7cded42d5664e993e` |

Firmware configuration reference files are stored in [`deploy/`](deploy/). The `build/` directory is reserved for generated local artifacts and is not tracked.

### Build from source

#### Prerequisites

- **QNX SDP 6.5** for cross-compiling the ARM32 QNX C hook and renderer.
- **Java 8, `lsd.jar`, and OSGi dependencies** for a complete Java source build. Extract `/mnt/app/eso/hmi/lsd/lsd.jxe` from the target firmware and convert it with `jxe2jar`.
- **GLFW 3 (optional, macOS local renderer testing)**: `brew install glfw`.

#### Complete Java source build

```bash
JAVA_HOME=/path/to/jdk8 \
JXE2JAR_DIR=../jxe2jar \
./build_java.sh
```

`LSD_JAR` and `OSGI_LIBS` may be set directly. Output: `build/carplay_hook.jar`. All Java classes are compiled with `-source 1.2 -target 1.2`.

#### Reproducible release JAR

```bash
./build_release.sh jar
```

This path uses repository compatibility stubs and `build_assets/carplay_hook_base.jar`, and writes `release/carplay_hook.jar` with deterministic archive metadata.

#### C hook

```bash
QNX_VM=192.168.64.16 \
QNX_USER=root \
QNX_PASSWORD=root \
./compile_hook.sh
```

Output: `build/libcarplay_hook.so`. Optional flags:

```bash
LOG=0 ./compile_hook.sh
LOG=1 LOG_RGD_PACKET_RAW=1 ./compile_hook.sh
```

#### Renderer

QNX deployment build:

```bash
QNX_VM=192.168.64.16 \
QNX_USER=root \
QNX_PASSWORD=root \
./compile_render_qnx.sh
```

Use `./compile_render_qnx.sh grid` for the debug grid. Windows users with a local QNX SDP installation may run `compile_render_qnx_windows.ps1`.

Local renderer development:

```bash
brew install glfw
make -C c_render
```

```powershell
.\compile_render_windows.ps1
```

Local test artifacts are written to `build/` and are not committed as release files.

#### Unified commands

```bash
./build_release.sh jar
./build_release.sh renderer
./build_release.sh hook
./build_release.sh all
./build_release.sh full
./build_release.sh verify
```

### Deployment files that differ from upstream

The two modified deployment files are:

- `carplay_hook.jar`
- `maneuver_render`

`libcarplay_hook.so` and `flag_atlas.rgba` remain unchanged from upstream.
