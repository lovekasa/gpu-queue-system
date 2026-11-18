# 技术事实与版本核查

日期：2026-11-18。结论：未发现技术事实层面的阻塞项。架构已区分用户选定基线、本地实测及创建工程前检查，未将客户端版本当成服务端事实；未执行数据库或业务测试。

| 核查对象 | 证据与判断 |
| --- | --- |
| Spring Boot 3.3.13 | 本地 Maven starter-parent POM 确有3.3.13，默认 java.version=17；官方3.3系统要求为Java17–23、Maven3.6.3以上，因此用户选Java21兼容。没有将缓存声称为当前项目pom |
| Vue 3/create-vue | 官方快速开始仍推荐create-vue；架构未声称已执行脚手架，精确版本延后已用户确认 |
| MySQL | 官方8.0文档确认InnoDB锁读及READ COMMITTED；锁固定资源行能避免空闲时无当前行可锁的问题。文档客户端8.0.34、服务端未核实的区分准确 |
| 事务代理与回滚 | Spring官方事务注解说明支持代理边界及自调用限制；架构显式要求受检异常也回滚，不把默认回滚规则误当成全异常回滚 |
| Session/CSRF/扫描 | Spring调度及Spring Security官方文档提供所需能力；架构不依赖分布式消息系统保证原子写入，站内消息是同库记录 |

## 创建工程前仍需复核的事项

当前Spring网页默认展示Framework7及Security7，Boot3.3.13应按其BOM使用匹配版本。ADR已经明确此边界，不能将网页当前版本直接引入依赖。Boot3.3.13不是本日最新版本，ADR也未声称仍有特定支持期限；维护状态在工程创建前核对，不擅自改变用户已确认的版本。

精确Vue/Node/构建工具、运行JDK/Maven、MySQL服务端补丁及schema迁移工具均已列明检查时点。本次不下载、不生成工程，不将这些延后项冒称已验证。

## 官方来源

- [Boot3.3系统要求](https://docs.spring.io/spring-boot/3.3/system-requirements.html)
- [Vue快速开始](https://vuejs.org/guide/quick-start.html)
- [MySQL8.0锁读](https://dev.mysql.com/doc/refman/8.0/en/innodb-locking-reads.html)
- [MySQL8.0隔离级别](https://dev.mysql.com/doc/refman/8.0/en/innodb-transaction-isolation-levels.html)
- [Spring事务注解](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)
- [Spring Security CSRF](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html)
