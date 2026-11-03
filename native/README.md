# 0.4.1：完整四阶段与阶段/分辨率对照

SM8750 默认四阶段 540p，另有四阶段 720p，旧两阶段 720/900/1080p 保留供对照；SM8550 与 Vulkan 回退保持两阶段。四阶段使用全部原始 block0–3，没有重训练或量化为 INT8。横屏模型 960×544 / 1280×736，竖屏交换长短边。

四阶段沿用前两阶段的稀疏重建；第二级完整 flow 与第三级输入在一次 GPU 提交中生成，第三级 flow 与第四级输入同样合并。末级 `rife_texture_flow_full.comp` 直接 PixelShuffle、累加、sigmoid 后写纹理，避免额外完整 FP32 flow 缓冲区和一次 host fence 等待。GPU↔NPU 依赖处的同步继续保留。普通映射内存仍以 QNN clientBuf 传入，不是已注册的共享 memHandle；没有全链路零拷贝声明。

诊断与画质实测见 `device-tests/RIFE-STAGE-AUDIT.md`；以下 0.4.0 段落保留原两阶段实现背景，默认档以本节为准。

# 0.4.0：8 Gen 2 / 8 Elite 的 GPU + QNN HTP

SM8550 使用 V73，SM8750 使用 V79。APK 同时保留两套 Stub/DSP Skel；QNN 在运行时选择硬件，Java 只对这两个已接入的 SoC 启用混合路径。V79 来源为同一固定 SHA256 的 QNN 2.42.0 AAR，DSP ELF 仍仅作为 asset；`verify_native.py` 逐字节核验两套库。CPU EP fallback 继续禁止，HTP 初始化失败则退回 ncnn Vulkan。
SoC 对照：[Qualcomm QAIRT 硬件支持](https://docs.qualcomm.com/nav/home/QNN_general_overview.html?product=924033590759186372)、[ExecuTorch Qualcomm 架构映射](https://github.com/pytorch/executorch/blob/main/backends/qualcomm/serialization/qc_schema.py)。

MediaCodec PRIVATE/AHardwareBuffer 仍硬解原片源。`RifeNative.createSession` 读取所在 Display 的实际长短边；保持片源比例，将合成限制在完整画面适应屏幕的尺寸。魅族 1440×3200 对 3840×2160 片源使用 2560×1440 图像；小于屏幕的源不放大。此界限与当前菜单/预览尺寸无关，填充和捏合缩放继续由视图处理。

`rife_texture_composer.h` 管理 RGBA8 前后帧和中间帧、FP32 flow/mask 纹理、immutable sampler、显式 image barrier/fence。解码导入融合 crop/resize，硬件支持独立 YCbCr reconstruction filter 时使用线性采样，否则手动双线性。归还 FOREIGN ownership 并等待完成后 Java 才关闭 Image。生产路径不读回图像，不转换 CPU 像素。

`rife_qnn.h` 将编码器与前两级卷积交给 ORT 1.23.2 / QNN 2.42.0 HTP，禁止 CPU fallback；缓存上一帧特征。交接使用 FP16 NHWC 张量与预分配输出；显式 flush/invalidate 和 GPU/host 屏障保留，QNN 内部复制没有消除。
`rife_hybrid_prepare_vec.comp` 只在下一级需要的采样位置重建第一级光流，避免完整 flow0。
`rife_texture_flow_fused.comp` 把第二级 PixelShuffle、resize、光流累加和 mask sigmoid 直接写入 FP32 纹理，避免完整 flow1 buffer 和额外提交等待。
`rife_texture_compose.comp` 用 FP32 手动光流插值及硬件 RGB 纹理采样合成显示尺寸的图像，直接 image blit 到 swapchain。

720p 档沿用横屏模型 1280×736、竖屏 448×768。SM8750 增加 900p 与 1080p 档：横屏模型 1600×928／1920×1088，竖屏模型 928×1600／1088×1920；默认 1080p，按源比例处理。源尺寸不足时使用较小模型，不放大估计图。精度切换保存进度和暂停状态，播放器解除绑定后重建 RIFE Surface/session；SM8550 不启用新档位。当前仅使用前两级细化，不能宣称完整模型质量。模型从原始锁定权重以 `device-tests/export_rife_qnn.py --nhwc --fp16` 导出，未使用 INT8 或重训练。

`VulkanPresenter.resizeSurface` 在 SurfaceView 尺寸变化时重建真正的 swapchain，修复菜单期间创建的较小 buffer 被放大到全屏。重建等待 GPU 空闲，重绘现有帧；处理与模型尺寸保持稳定。`RifeMemcView` 可选 PerformanceHintManager 会话报告实际工作耗时和中间帧截止时间预算；不可用时继续正常播放，退出时关闭，不改变全局性能设置。

`RifeFrameClock` 按 Media3 的 releaseTime 匹配 Image 时间戳，用媒体 PTS 和播放速度求帧间隔，避免把 VSYNC 调整误当视频帧率。队列有界，seek/暂停/倍速变化使旧配对失效。

启用 QNN 的 RIFE 会话还将调用线程限制到允许的性能核，减少启动加速结束后的主机侧延迟；不设置设备频率。结束时恢复该线程原来的 CPU affinity，失败则继续系统默认调度。该策略的耗电和长时发热仍需实测，不能将几分钟样本当成持续散热验收。

`prepare_qnn.py` 校验官方 Maven AAR SHA，arm64 ORT/HTP/Prepare/V73 和 V79 Stub/System 作为 JNI 库；DSP Skel 是 asset。模型、运行库和原作者许可保留。SM8550/SM8750 以外或 HTP 初始化失败时保留原 ncnn Vulkan，OpenGL 可选；HDR 仍原生输出。

数值对照、完整播放、SurfaceFlinger 实际呈现、长时散热及复杂画质分开验收；对应哈希与原始记录见根 HANDOFF。独立 benchmark 不包括解码和显示，不能替代实际播放。

以下保留原 Vulkan 路径的说明与历史验证范围。

# RIFE Vulkan 插帧后端

入口为正式播放时「更多 → MEMC 插帧」，可选关闭、OpenGL 运动补偿、RIFE AI · Vulkan。
默认继续使用已有 OpenGL；选择持久保存在 `memcBackend`，与启用开关分开。
切换保留播放进度与暂停状态。HDR 和高帧率仍走原生 SurfaceView。

## 构建

安装 Android NDK r27d（或兼容的新版本）、CMake 3.22+、JDK17 和原工程 Android SDK：

```sh
export ANDROID_NDK_HOME=/path/to/android-ndk-r27d
python3 build.py
python3 verify.py
python3 verify_native.py
python3 device-tests/build_checks.py
```

`build_native.py` 会校验 `native-dependencies.lock.json`，取得固定的 ncnn 20260526
完整源码，在 Android API 30 编入 AHardwareBuffer 接口，生成 arm64-v8a 的 `libthor_rife.so` 并打包进 APK。
模型已随源码保存，首次启动从 assets 校验并复制到 App 私有目录，不在播放时联网下载。
原生库按 16 KiB ELF 页对齐，C++/OpenMP/ncnn 静态链接，不需要额外的共享 STL。
当前 native ABI 为 arm64-v8a，对应 Thor 和魅族测试机。

并行维护时使用独立构建输出，不并行复用同一个目录：

```sh
export THOR_BUILD_DIR="$PWD/build/rife-gpu-validation"
python3 build.py
python3 verify.py
python3 verify_native.py
python3 device-tests/build_checks.py
```

## 实现和边界

- `MemcView` 是现有播放器接口的 facade，原算法完整保留在 `GlMemcView`。
- Media3 继续管理媒体时钟、音频、网络和 seek。RIFE Surface 的视频 decoder 只选择
  MediaCodec 硬件 decoder；音频 decoder 选择不受影响。
- `RifeMemcView` 使用 PRIVATE ImageReader，仅请求 GPU_SAMPLED_IMAGE，不请求 CPU
  可读缓冲区，也不调用 getPlanes。Android 13+ 对有效 acquire fence 等待完成后导入 AHardwareBuffer；
  空 fence 表示没有待等待的同步对象，不把其 await=false 误判为超时。
  Android 11/12 因缺少这里使用的公开 Java 同步接口回退，仍可选择 OpenGL。
- GPU YCbCr 采样按 Image dataspace（缺失时取 Media3 ColorInfo）选择 601/709/2020 矩阵和范围；
  元数据均未知时采用驱动建议。crop、归一化、32 倍数边缘复制和 timestep=0.5 都在 GPU 上完成。
- ncnn Vulkan **RIFE 4.12 Lite / ensemble=False** 实时档提取前两级的 flow/mask，支持时用
  FP16 存储和运算。模型运动估计长边为 128 像素（1080p 对应 128×72，补边到 128×96），
  `rife_compose.comp` 将 flow 缩放回原尺寸，对原尺寸两帧进行双线性 warp 和 mask 合成。
  输出保留原分辨率，但低分辨率、少级数运动估计会降低复杂运动精度。加载后检查模型各层
  Vulkan 支持，避免退到 CPU 算整帧。最高接受 4096×4096 crop，4K 实时尚未验证。
- `vulkan_presenter.cpp` 与 ncnn 共用 device 和唯一 compute/present queue。着色器把结果打包为
  GPU RGBA buffer，再由 GPU copy/blit 写入 FIFO swapchain；没有输出 Mat 读回、CPU RGB→RGBA
  循环或 staging 上传。仍有 GPU 内部转换/复制，不能称为完全没有内存搬运。
- `native/ncnn-ahb-sync.patch` 为固定源码补上 FOREIGN 队列所有权 acquire/release、GENERAL
  layout、buffer 屏障、规范化 YCbCr 采样坐标和 packed RGBA8 GPU 导入；限制一个 compute queue。
  导入显式传入 buffer 尺寸，避开 ncnn 重载读取未初始化 rotate_from 导致宽高交换的问题。
  ncnn 自定义 shader 的 push constant 结构命名为 parameter，创建时校验反射字段数。
  JNI 全局互斥串行化新旧 Surface session，展示等待命令 fence；每个 swapchain image 持有
  独立呈现 semaphore，移除逐帧 queueWaitIdle。源帧与中间帧复用足够大的 GPU 图像。
- 输入导入对象、采样 pipeline 按 AHardwareBuffer 的稳定 allocation ID/色彩空间缓存，最多 64 项；持有 AHB 引用，
  淘汰时先销毁 Vulkan 资源再释放引用。GPU 导入结束立即关闭 Image，让解码器复用缓冲区。
  CPU 只处理元数据、调用调度及同步等待；初始化的模型文件读取和权重上传仍在 CPU 侧。
- 原帧/中间帧采用一帧 lookahead，额外视频延迟约一个源帧，音画同步尚未校准。
  已到显示时刻的中间帧在当前 worker 回调内直接呈现，避免被下一解码回调抢先作废。
  队列不无限积压；过期中间帧丢弃，连续 12 次推理超出源帧预算会报错退回普通播放。
  统计显示真实推理调用结束耗时（含 GPU 等待），不是仅 CPU 提交耗时，也不是屏幕实测 FPS。
- 旋转轨道暂时拒绝 RIFE，提示使用 OpenGL；HDR 拒绝进入 SDR 转换。
  初始化、模型、纹理、设备丢失或持续性能不足会恢复普通播放，原因保存在播放信息。
- 0.3.21 魅族实测本地 1080P30 输出提交约 57.7fps、B站 720P30 约 59.3fps，六秒窗口包含
  启动影响；不是独立屏幕呈现测量。4K、遮挡画质、持续散热、音画同步及 Thor 尚未验收。

## 依赖来源

- [ncnn 20260526](https://github.com/Tencent/ncnn/releases/tag/20260526)，BSD-3-Clause。
- [nihui RIFE Warp](https://github.com/nihui/rife-ncnn-vulkan/tree/a7532fc3f9f8f008cd6eecd6f2ffe2a9698e0cf7)，MIT。
  本地唯一兼容修改是关闭 ncnn 新版已移除的 `use_shader_pack8` 分支，保留 pack1/4。
- [RIFE 4.12 Lite ncnn 模型](https://github.com/styler00dollar/VapourSynth-RIFE-ncnn-Vulkan/tree/c3ec6aabc07c8fa37a4f58d7fed9e2ad1fc1b13f/models/rife-v4.12_lite_ensembleFalse)，
  转换项目 MIT；原模型来自 [Practical-RIFE](https://github.com/hzwer/Practical-RIFE)，MIT。
- 完整许可证与模型 SHA256 随 APK assets 保存；锁文件同时记录模型、ncnn 源码/补丁和 Warp 来源。

## 验证

旧 CPU 转色工具已不在生产路径；其历史主机测试覆盖有限/全范围、BT.601 红色、plane padding、NV12 式交错平面、裁剪、越界和 HDR 拒绝：

```sh
c++ -std=c++17 -fsanitize=address,undefined native/tests/yuv_rgb_test.cpp -o /tmp/thor-yuv-test
/tmp/thor-yuv-test
```

`verify_native.py` 校验实际 APK 的 AArch64 ELF、16K 对齐、JNI 入口和模型/许可证内容。
设备专项应静音使用 `RifePhoneChecks`，不运行遗留 PhoneChecks.onStart/checkSticks：

```sh
adb -s SERIAL shell am instrument -w local.thor.bilichannels.tests/local.thor.bilichannels.RifePhoneChecks
```

需先在 App 外部 files 的 `memc-validation/` 放好 `SDR-1080p30-silent.mp4`。
该驱动默认使用本地无声素材；`-e mode live` 读取真实 B站视频，移除音频轨道并保持 App 静音。
切换 GL/RIFE/关闭，检查推理、暂停、seek 和进度保持；
报告中的帧率只是提交速率，需另采 SurfaceFlinger 时间戳做呈现验收。

### 原 App 的 GPU 集成测试

`device-tests/RifeGpuChecks.java` 复用既有 instrumentation 测试入口，目标仍是原 App。
不增加产品包或第二个 App。使用无音轨色块素材，硬件 decoder 输出 PRIVATE AHB，原帧及
RIFE 中间帧经生产 Vulkan presenter 输出到测试 ImageReader；只有测试接收端读回像素，
核对 RGB 顺序、有限范围、非 32 倍数补边、reset 和缓冲区复用，并记录导入线程 CPU 时间。
默认 318×178 色块检查不代表 1080p/4K 实时性能。`-e mode motion` 使用 1080p 运动素材，
验证中间帧区别于两个端点及简单均值；测试输出缩至 318×178 取样，不代表全面画质评价。

将 `RIFE-colors-silent.mp4` 放入原 App 外部 files 的 `memc-validation/`，在已安装主包及
既有 device-checks instrumentation 后运行：

```sh
adb -s SERIAL shell am instrument -w local.thor.bilichannels.tests/local.thor.bilichannels.RifeGpuChecks
```

魅族安装的是同包静音自检版本，构建方法见 `device-tests/README.md`。该版本上述命令的
instrumentation 包名前缀使用 `local.thor.bilichannels/`，不安装第二个应用。

## Android model-load crash fix (0.3.24)

`NCNN_OPENMP=OFF`: Vulkan/QNN performs inference; ncnn CPU model packing already
uses one thread. Remove the unused static libomp runtime whose affinity setup
aborted inside `MemoryData_vulkan::upload_model` on Meizu (2026-10-15).
Keep the session worker's performance-core affinity and GPU/NPU implementation.
`verify_native.py` checks the packaged ELF for absence of the libomp fork/abort
symbols; repeated device session creation remains a separate runtime check.
