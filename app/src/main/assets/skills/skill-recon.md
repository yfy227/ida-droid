# 样本初诊三步法

## 何时用
拿到未知二进制/样本的第一时间, 先做全维度快诊再决定路线。

## 流程
1. **基础指纹**: file 命令 + md5/sha256 + 大小; APK 则 aapt dump badging 看包名/入口/SDK
2. **壳/保护检测**: 检查 DEX 头魔数、libjiagu/libDexHelper 等壳 so 特征、段熵(>7.2 提示加密)
3. **入口定位**: AndroidManifest 入口类 + so 的 JNI_OnLoad/init_array 优先级排序

## 工具组合
pull_file → run_shell(file/md5sum) → mcpc(打开样本) → ida-mcp(入口函数反汇编)

## 注意
- 输入必须原始未修改文件(修改过的输入污染证据链)
- 先诊后动: 壳没确认前不要急着静态分析加密区
