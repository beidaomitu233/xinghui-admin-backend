# 星辉后台管理系统 (XingHui Admin)

星辉后台管理系统（XingHui Admin）是一款基于 **Spring Boot 3**、**MyBatis-Plus 3**、**Spring Security** 和 **Redisson** 构建的现代化企业级 SaaS 后台开发脚手架。项目采用模块化多租户设计，整合了一站式云存储、多通道短信、三方社交登录以及注解级防护（限流、防重）等高频业务组件，旨在帮助开发者快速构建安全、稳定、可扩展的分布式业务系统。

---

## 🌟 核心特性

*   **SaaS 多租户架构**：内置多租户隔离机制，基于 MyBatis-Plus 拦截器自动拼接租户 ID 过滤，支持行级数据隔离，轻松应对 SaaS 应用开发。
*   **一站式云存储 (OSS)**：基于 `x-file-storage` 深度整合，支持本地存储、MinIO、阿里云 OSS、腾讯云 COS、七牛云等数十种存储平台。支持大文件**分片断点续传**（自动分片记录入库）与**流式传输**（有效规避 OOM）。
*   **高并发限流防护**：提供自定义 `@RateLimiter` 注解，基于 Redisson 滑动窗口实现细粒度的接口限流（支持根据 IP、用户、全局等多维度配置限流阈值）。
*   **分布式防重提交**：提供 `@RepeatSubmit` 注解，利用 Redis 分布式锁拦截接口的表单重复提交动作，确保数据一致性。
*   **细粒度数据权限**：基于 SQL 解析器的动态数据权限切面拦截机制，支持根据部门、角色、个人等层级范围实现动态 SQL 重写与过滤。
*   **多通道短信聚合 (SMS)**：集成 `SMS4J` 框架，开箱即用支持阿里云、腾讯云、网易云信等多平台短信接口，且内置防刷限流机制。
*   **多端社交联合登录**：通过 `JustAuth` 快速整合第三方社交登录（如微信、Gitee、GitHub、支付宝等），简化账户绑定流程。
*   **模块化脚手架**：预置用户管理、角色权限、菜单管理、系统参数、定时任务调度（带日志追踪）、新闻资讯、软件下载管理等基础模块。

---

## 📂 模块职责划分

```
xinghui-admin
├── common (通用组件包)
│   ├── common-core          // 核心依赖、通用常量、基类 Entity/DTO、全局异常拦截
│   ├── common-redis         // 基于 Redisson 的 Redis 缓存与分布式锁工具类
│   ├── common-security      // 基于 Spring Security + JWT 的认证授权模块与加密工具
│   ├── common-oss           // 多平台对象存储服务，统一文件与分片持久化组件
│   ├── common-tenant        // 多租户隔离机制与上下文处理器
│   ├── common-ratelimiter   // 基于 Redisson 锁的高性能接口限流拦截器
│   ├── common-idempotent    // 基于 Redis 锁防重复提交组件
│   ├── common-mail          // Hutool 邮件服务发送器封装
│   ├── common-sms           // SMS4J 短信聚合服务扩展
│   └── common-social        // JustAuth 三方社交联合登录组件
├── system-framework (系统框架层)
│   ├── config.mybatis       // MyBatis-Plus 元数据自动填充（createTime/updateTime审计）
│   ├── config.datascope     // 动态数据权限过滤解析处理器
│   └── filter               // 登录凭证 JWT 验证过滤器
└── system-admin (系统核心业务模块，主启动入口)
    ├── controller           // 用户、菜单、系统配置、下载、资讯及文件管理 API
    ├── domain               // 数据实体（SysUser, SysRole, SysMenu, SysFilePart 等）
    ├── mapper               // 数据库持久化 Mapper 接口与 SQL 映射
    ├── service              // 业务层逻辑实现
    └── recorder             // FileRecorderImpl 对象存储上传记录器与分片续传持久化
```

---

## 🛠️ 技术选型

### 后端技术栈
| 技术 | 说明 | 版本 |
| :--- | :--- | :--- |
| **Spring Boot** | 核心开发框架 | 3.3.6 |
| **MyBatis-Plus** | 数据库 ORM 增强工具 | 3.5.10 |
| **Spring Security** | 安全认证与授权 | 3.3.6 |
| **Redisson** | Redis 客户端及分布式锁 | 3.40.2 |
| **Hutool** | Java 核心工具包（工具、JWT、Mail等） | 5.8.38 |
| **x-file-storage** | 一站式对象存储客户端 | 2.2.1 |
| **SMS4J** | 短信聚合框架 | 3.3.5 |
| **JustAuth** | 第三方登录聚合客户端 | 1.16.7 |
| **Druid** | 阿里数据库连接池 | 1.2.25 |
| **MySQL Connector** | 数据库驱动 | 8.0.33 |

---

## 🏃 快速开始

### 1. 环境依赖
*   **Java 17** 及以上编译运行环境
*   **Maven 3.8+** 项目构建工具
*   **MySQL 8.0** 关系型数据库
*   **Redis 6.x+** 高性能缓存服务
*   **MinIO**（可选，用于本地云存储调试）

### 2. 初始化数据库
在 MySQL 中创建数据库 `xinghui_admin`，并导入 [/sql/xinghui_admin.sql](file:///d:/changhuiengineer/DevProject/xinghui-admin2/springboot-admin-xinghui/xinghui-admin/sql/xinghui_admin.sql) 数据库脚本，完成基础表结构与预置菜单/租户数据的初始化。

### 3. 系统配置
修改 `system-admin/src/main/resources/application.yml` 与 `application-datasource.yml` 文件：
1.  **数据库连接**：修改 `spring.datasource.dynamic.datasource.master` 的 `url`、`username` 及 `password`。
2.  **Redis 连接**：修改 `spring.data.redis` 的主机地址、端口及密码。
3.  **云存储客户端**（MinIO / 阿里云等）：配置 `dromara.x-file-storage` 各平台的秘钥及 Bucket 桶名，或在系统环境变量中注入 `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY` 等参数。

### 4. 编译与启动
在项目根目录下，使用命令行执行：
```bash
# 清理并完成项目打包
mvn clean package -DskipTests

# 启动业务主程序
java -jar system-admin/target/system-admin-1.0-SNAPSHOT.jar
```
启动成功后，即可通过默认配置的端口（如 `7799` 或 `8030`）访问后台 RESTful API 接口。

---

## 💡 功能组件配置与使用

### 1. 对象存储的使用 (x-file-storage)
支持在业务层中直接依赖 `FileStorageService` 操作文件：
```java
@Autowired
private FileStorageService fileStorageService;

// 上传本地文件到云端
FileInfo fileInfo = fileStorageService.of(file).upload();

// 流式下载大文件（零堆常驻，避免 OOM）
fileStorageService.download(fileUrl).inputStream(in -> {
    IoUtil.copy(in, response.getOutputStream());
});
```

### 2. 接口限流配置 (@RateLimiter)
用于防止高频爆刷接口（如短信、验证码、登录 API 等）：
```java
// 限制同一个手机号 60 秒内只能调用该接口 5 次
@RateLimiter(key = "#loginDTO.phone", time = 60, count = 5, message = "操作过于频繁，请稍后再试")
@PostMapping("/login")
public Result<String> login(@RequestBody LoginDTO loginDTO) {
    return loginService.login(loginDTO);
}
```

### 3. 表单重复提交拦截 (@RepeatSubmit)
用于控制接口短时间内不能以相同参数重复触发：
```java
// 限制 5 秒内不能使用相同参数重复发起请求
@RepeatSubmit(interval = 5000, message = "请勿重复提交订单")
@PostMapping("/submit")
public Result<Void> submitOrder(@RequestBody OrderDTO orderDTO) {
    return orderService.submit(orderDTO);
}
```
