# 离线拼音词库（0.3.6）

本版合并去重后包含 899,828 个词条、787,417 个连写拼音键。紧凑索引 assets/phrases.bin 约 24.5 MB，APK 内压缩保存；运行时共享一份字节数组和偏移索引，二分查询，不在每次点击时遍历词库。

来源：

- [Rime 袖珍简化字拼音](https://github.com/rime/rime-pinyin-simp)，提交 0c6861ef7420ee780270ca6d993d18d4101049d0，Apache-2.0。原文件、作者和许可证在 assets/rime-phrases-*，原哈希见 phrase-provenance.json。
- [雾凇拼音](https://github.com/iDvel/rime-ice)，提交 9e66b0729083b37d217312294f6d516c8d7234be，2026-09-23 获取，base/ext 原始词库完整保存在源码包的 dictionary-sources/，保留上游来源说明。GPL-3.0 许可证在 assets/rime-ice-LICENSE.txt。
- dictionary-sources/thor-extra.tsv 是本项目增加的动漫、游戏、网络用语、汽车和日本影视/成人影视行业的中性词汇；不包含资源网址或联网采集。

构建方法：python3 make_phrase_index.py。按拼音和文字去重、按上游频率排序，生成的词库及全部输入哈希记录在 expanded-phrase-provenance.json。词库数据的对应源码、生成脚本和许可证随完整交付包提供。

这是离线候选词库，不是实时网络热词服务，不保证覆盖全部流行词。系统输入法的语音和在线联想由设备上安装的输入法提供，评论/回复/弹幕默认使用系统输入法。
