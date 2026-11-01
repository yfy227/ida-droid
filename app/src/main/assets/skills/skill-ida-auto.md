# ida-mcp 自动化循环

## 何时用
需要批量处理函数(命名/注释/结构体/交叉引用), 手点太慢。

## 流程
1. mcpc 打开样本, 确认自动分析完成(wait_for_analysis)
2. 循环: list_functions → 按 heuristic 逐个 set_name/set_comment
3. 交叉引用: xrefs_to 入口 → 传播命名(func_XXXX → sub_XXYY 语义化)
4. 结构体: 常见偏移模式 → create_struct + apply

## 提效要点
- 每批 50-100 函数, 中间落盘(防会话中断丢进度)
- 命名先粗后细: 第一轮只按调用图拓扑排序命名, 第二轮按字符串引用精化
