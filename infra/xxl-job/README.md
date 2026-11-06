# XXL-JOB 上游资料

固定版本3.1.1，Java17/Spring Boot3兼容线；不是“最新版本”声明。
`upstream-schema.sql`与`LICENSE`来自xuxueli/xxl-job的3.1.1 tag，保留GPL-3.0及原版权。
来源：https://github.com/xuxueli/xxl-job/tree/3.1.1

初始化脚本只取建表部分，在独立xxl_job_project数据库创建项目执行器、停用的补偿任务和随机密码管理员；不导入上游演示任务或示例API Key。
仅本机学习，管理员是可信运维角色。XXL执行器具有上游GLUE能力，不是安全沙箱，9999端口不对外发布。
本项目链接GPL-3.0的xxl-job-core；若分发组合Java应用，应保留GPL-3.0及相应完整源码，不把此依赖宣称为MIT/Apache。
