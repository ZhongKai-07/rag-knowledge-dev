-- Ragent 初始数据（PostgreSQL）
-- 仅包含系统启动所需的最小数据集

-- 默认管理员账户（密码: admin，生产环境请立即修改）
INSERT INTO t_user (id, username, password, role, create_time, update_time, deleted)
VALUES ('2001523723396308993', 'admin', 'admin', 'admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
ON CONFLICT (username) DO NOTHING;
