-- Ragent 初始数据（PostgreSQL）
-- 仅包含系统启动所需的最小数据集

-- 默认部门
INSERT INTO public.sys_dept (id, dept_code, dept_name) VALUES
    ('1', 'GLOBAL', '全局部门')
ON CONFLICT (id) DO NOTHING;

-- 默认角色
INSERT INTO public.t_role (id, name, description, role_type, max_security_level) VALUES
    ('1', '超级管理员', '系统超级管理员，绕过所有 RBAC 过滤', 'SUPER_ADMIN', 3),
    ('2', '普通用户',   '默认普通用户角色',                    'USER',        0)
ON CONFLICT (id) DO NOTHING;

-- 默认管理员账户（密码: admin，生产环境请立即修改）
INSERT INTO public.t_user (id, username, password, role, dept_id, avatar, create_time, update_time, deleted)
VALUES ('1', 'admin', 'admin', 'SUPER_ADMIN', '1', 'https://avatars.githubusercontent.com/u/583231?v=4', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
ON CONFLICT (id) DO NOTHING;

-- 用户-角色关联
INSERT INTO public.t_user_role (id, user_id, role_id) VALUES
    ('1', '1', '1')
ON CONFLICT (id) DO NOTHING;
