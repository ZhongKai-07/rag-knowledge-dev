# Database Migration Notes

## `upgrade_v1.5_to_v1.6` 需要环境专属脚本

`upgrade_v1.5_to_v1.6` 不只是 DDL，还包含 `t_role.dept_id` 的数据回填。回填依赖各环境现有的 `t_role.id` 和 `sys_dept.id`，所以不能共用一份通用 SQL。

- `upgrade_v1.5_to_v1.6.local-dev.sql`
  只给当前仓库的本地开发 fixture 用，里面的 snowflake id 不可复制到其他环境。
- `upgrade_v1.5_to_v1.6.template.sql`
  作为新环境脚手架。上线前请复制为 `upgrade_v1.5_to_v1.6.<env>.sql`，再把每一处业务角色映射补齐。

## 其他环境上线前 checklist

1. 先对账现有角色：
   `SELECT id, name, role_type FROM t_role WHERE deleted = 0 ORDER BY id;`
2. 再对账部门：
   `SELECT id, dept_code, dept_name FROM sys_dept ORDER BY id;`
3. 按真实归属把每个业务角色写成显式 `UPDATE t_role SET dept_id = '...' WHERE id = '...';`
4. 执行后确认没有漏项：
   `SELECT id, name, role_type FROM t_role WHERE dept_id IS NULL AND deleted = 0;`
5. 只有第 4 步结果为 0 行，才能继续跑 `upgrade_v1.6_to_v1.7.sql`。

## 为什么这里必须 fail-closed

`upgrade_v1.6_to_v1.7.sql` 顶部有 pre-check：如果 `t_role.dept_id` 仍有空值，它会直接报错并拒绝继续。那是这次 backfill 跑漏时的最后兜底，不应该替代环境映射本身。
