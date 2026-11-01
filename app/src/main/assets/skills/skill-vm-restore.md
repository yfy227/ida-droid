# VM/字节码还原

## 何时用
样本带 VM 保护(自研字节码+解释器)。

## 流程
1. **调度表定位**: dispatcher 的 switch/跳表结构, dump 全部 handler 地址
2. **handler 枚举**: 每个 handler 的语义(单操作/多操作), 打 opcode 表
3. **语义还原**: opcode → 寄存器/栈动作, 对照 IR 还原原始逻辑
4. **产物**: vmopcode 表 + 反汇编器 + 还原后的伪代码

## 工具组合
mcpc(decompile dispatcher) → 脚本批处理 handler 批量分析

## 注意
- 先数 handler 总数, 判断是全 VM 还是半 VM(部分指令直通 native)
