# Frida 动态跟踪

## 何时用
需要运行时行为(参数/返回值/调用链)而静态看不清。

## 三件套模板
1. **方法 hook**: Java.use + implementation 替换, 打印参数+返回
2. **native hook**: Interceptor.attach onEnter/onLeave, hexdump args
3. **主动调用**: Java.choose 已有实例 或 Java.cast 调用隐藏方法

## 工具组合
run_shell(生成 frida_js 模板) → frida-helper 插件的 frida_java_scaffold → 设备运行

## 注意
- hook 前先确认进程存活(attach 时机)
- 输出加 tag 前缀方便事后 grep
