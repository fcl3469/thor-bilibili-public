# RIFE convolution islands

These ONNX models contain the pinned RIFE 4.12 Lite weights, partitioned into an encoder and refinement convolution blocks. Legacy profiles contain two blocks; `*-full-540` and `*-full-720` contain all four original blocks. FP16 graph I/O and constants reduce the Vulkan/QNN interface size; this is not INT8 quantization or retraining. Flow coordinates remain FP32 in the GPU reconstruction/composition path.

Export: `device-tests/export_rife_qnn.py --width 1280 --height 720 --stages 2 --nhwc --fp16 --output DIR` (landscape); `--width 448 --height 768` (portrait). Original model SHA-256 and padded shapes are recorded in each manifest. Retain the licenses in `assets/rife-v4.12-lite/MODEL-LICENSE.txt` and `CONVERSION-LICENSE.txt`.

The Android benchmark compares each island against the original GPU model. FP16 backends are not bit-identical. Synthetic numerical checks do not constitute general interpolation quality acceptance; the legacy profiles stop after two trained refinement stages.

SM8750 high-precision profiles use the same exporter/weights: `--width 1600 --height 900` and `--width 1920 --height 1080` for landscape, swapped for portrait. These profiles are independent of the legacy SM8550 profiles. Dimensions are rounded to multiples of 32; source images are never enlarged for estimation. The tested 2560x1440 candidate exceeded the real-time budget and is not packaged.

Complete SM8750 profiles: `--stages 4 --nhwc --fp16 --width 960 --height 540` and `--width 1280 --height 720`, swapped for portrait. The production path retains sparse stage-0 reconstruction, fuses each later flow reconstruction with the following preparation, and writes stage-3 results directly to flow/mask textures. No retraining or weight pruning. Original ensemble=False remains unchanged.
