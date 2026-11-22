# Story 1.1 验证记录

2026-11-19 完成静态源码走查。故事处于审阅状态。

## 已核对

- `java -version`、`javac -version` 和 `mvn -version` 确认本机可用 Java 21.0.9 与 Maven 3.8.8。Node.js 为 24.12.0，npm 为 11.18.0。Java 21 来自本机已有的 JetBrains Runtime，通过当前命令进程的 `JAVA_HOME` 使用。
- 使用官方 create-vue 3.24.0 生成 Vue 项目。npm 生成锁文件并通过 `--engine-strict` 检查。直接依赖与锁文件一致，其中 Vue 3.5.45、Vite 8.3.8、TypeScript 6.0.3。后端 POM 固定 Spring Boot 3.3.13、H2 2.2.224 和 Flyway 10.10.0。
- 走查启动脚本、账号迁移、Spring Security 和 Vue 调用顺序。确认验收路径从系统临时目录生成，本地初始化使用交互口令，前端提交会话写入前先取 CSRF 令牌。差异空白和 PowerShell 脚本静态语法检查通过。

## 验收状态

| 验收 | 实际记录 |
| --- | --- |
| 1. 工程和版本 | 已检查脚手架、工具版本、POM 与前端锁文件。 |
| 2. 文件库与迁移 | 已走查数据位置、迁移步骤和非零退出处理，待执行迁移与启动验收。 |
| 3. 初始管理员 | 已走查非 Web 初始化、散列写入和重复初始化路径，待核实临时库持久化结果。 |
| 4. 登录和本人身份 | 已走查 API、会话、HttpOnly 配置与页面，待实际登录。 |
| 5. 拒绝无效或越权请求 | 已走查认证、角色、CSRF 及错误反馈分支，待实际请求。 |
| 6. 退出和原会话失效 | 已走查 Spring Security 退出及页面清理，待核对原会话。 |
| 7. 新环境独立验收 | [运行手册](../../../docs/development/runbook.md) 已给出步骤，验收尚未执行。 |

## 本机构建

2026-11-19 21:12（Asia/Shanghai），在当前进程选用 Java 21.0.9 后，运行 `mvn.cmd -B -ntp -f backend/pom.xml '-Dmaven.test.skip=true' package`，结果为 `BUILD SUCCESS`，生成 `backend/target/gpu-queue.jar`。测试已跳过。当前为开发用后端 JAR，前端通过 Vite 运行。

数据库迁移、应用启动、业务测试和 smoke 实验尚未执行。

## 2026-11-22 macOS 运行验收

环境为 Apple Silicon、JDK 21.0.12.1、Maven 3.9.16、Node.js 24.12.0、npm 11.6.2。Vue 类型检查、Vite 构建和 Maven 打包通过。交付 JAR 包含前端构建资源，依赖版本沿用 POM 和锁文件。验收使用独立临时 H2 文件库，实际主用例路径为 `/var/folders/9x/bkwvhwq96kx5_7f07c9jl9fm0000gn/T/gpu-queue-test-j5r7ds_q/gpu-queue.mv.db`。浏览器用例和迁移失败用例使用各自的临时目录。

| 验收 | 实际结果 |
| --- | --- |
| 1. 工程和版本 | 确认工具版本，沿用 Spring Boot 3.3.13、H2 2.2.224、Flyway 10.10.0 及前端锁文件。 |
| 2. 文件库与迁移 | 首次 `migrate` 应用 V1，第二次无待应用迁移。首页及构建后的 JS 均返回 200。迁移失败时命令非零退出，8080 保持关闭。Flyway 对 H2 2.2.224 打印兼容性提示，两次迁移均完成。 |
| 3. 初始管理员 | 交互初始化成功，重复运行报告管理员已存在。只读查询确认仅一行 ADMIN，口令散列符合 96 位十六进制格式。初始化命令在非 Web 模式运行，HTTP 初始化请求分别被拒绝为 401 和 403。 |
| 4. 登录和身份 | 合法 CSRF 登录返回 204，`/api/me` 返回 200 和 ADMIN 身份，Session Cookie 带 HttpOnly。大小写与首尾空白变体登录后仍为同一账号。Safari 页面显示姓名、登录名和管理员角色。 |
| 5. 拒绝无效请求 | 匿名 `/api/me` 与错误口令均返回 401。缺少 CSRF 的登录和退出返回 403。已登录时，缺失或无效 CSRF 的退出均返回 403，会话保持有效。Safari 显示通用的登录错误提示。 |
| 6. 退出 | 合法退出返回 204，旧会话请求 `/api/me` 返回 401。Safari 点击退出后回到登录表单。服务日志未出现测试口令或原会话值。 |
| 7. 独立验收 | 在独立临时库依次完成 `migrate`、`init-admin`、`serve` 和 HTTP/浏览器核查，复现步骤见 [macOS 启动](../../../docs/development/runbook.md#macos-启动)。 |
