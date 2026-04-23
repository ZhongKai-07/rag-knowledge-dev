# 开发笔记

随手记的零散思考、概念澄清、调试过程、小型 Q&A。**不追求形式完整**。

## 放这里

- 调试时搞明白的"这段代码为什么这么写"
- 概念澄清（"X 是干什么的"、"Y vs Z 区别"）
- 一次改动的投入产出思考
- 看 code review 时的顿悟片段

## 不放这里

- 完整的 PR 事件 → `log/dev_log/`（有日期 index）
- 原始日志 / JSON / error 样本 → `log/diagnostic/`
- 固化后的坑点 → `docs/dev/gotchas.md`
- 设计方案 → `docs/dev/design/`

## 命名

`YYYY-MM-DD-topic.md`。日期前缀让"最近想过什么"一目了然，和 `dev_log/` 对齐。

## 提升路径

笔记写着写着觉得"这值得别人看到"时，搬到对应的 `docs/dev/` 子目录，
原地留个占位说 "→ 已提升至 ..."，或直接 `git rm`。
