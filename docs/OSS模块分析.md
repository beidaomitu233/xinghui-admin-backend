# OSS 对象存储模块分析

## 一、模块概述

RuoYi-Vue-Plus 的对象存储模块基于 **AWS S3 SDK v2** 实现，采用统一协议支持所有兼容 S3 协议的云厂商。

### 1.1 支持的存储厂商

| 厂商 | 类型 | 说明 |
|------|------|------|
| MinIO | 自建 | 开源对象存储，适合私有化部署 |
| 阿里云 OSS | 公有云 | 阿里云对象存储服务 |
| 腾讯云 COS | 公有云 | 腾讯云对象存储 |
| 七牛云 Kodo | 公有云 | 七牛云对象存储 |
| 华为云 OBS | 公有云 | 华为云对象存储 |

### 1.2 技术栈

- **AWS S3 SDK v2**（software.amazon.awssdk:s3）
- **Netty 异步客户端**（高性能非阻塞 I/O）
- **S3 Transfer Manager**（高级传输管理）
- **S3 Presigner**（预签名 URL 生成）

---

## 二、架构设计

### 2.1 目录结构

```
ruoyi-common-oss/                          # 公共组件模块
├── constant/OssConstant.java              # 常量定义
├── core/OssClient.java                    # 核心客户端
├── entity/UploadResult.java               # 上传结果实体
├── enums/AccessPolicyType.java            # 桶权限类型枚举
├── exception/OssException.java            # OSS 异常类
├── factory/OssFactory.java                # 工厂类
└── properties/OssProperties.java          # 配置属性类

ruoyi-modules/ruoyi-system/                # 业务模块
├── controller/system/SysOssController.java         # 文件管理控制器
├── controller/system/SysOssConfigController.java   # 存储配置控制器
├── domain/SysOss.java                     # 文件实体
├── domain/SysOssConfig.java               # 配置实体
├── service/ISysOssService.java            # 服务接口
└── service/impl/SysOssServiceImpl.java    # 服务实现
```

### 2.2 核心流程

```
前端 → SysOssController → ISysOssService → OssFactory → OssClient → 云存储
```

---

## 三、核心组件详解

### 3.1 OssProperties（配置属性）

| 属性 | 说明 | 示例 |
|------|------|------|
| tenantId | 租户 ID | 000000 |
| endpoint | 服务地址 | oss-cn-hangzhou.aliyuncs.com |
| domain | 自定义域名 | cdn.example.com |
| prefix | 路径前缀 | uploads |
| accessKey | 访问密钥 ID | LTAI5t... |
| secretKey | 访问密钥 Secret | abc123... |
| bucketName | 存储桶名称 | my-bucket |
| region | 存储区域 | cn-hangzhou |
| isHttps | 是否 HTTPS | Y/N |
| accessPolicy | 桶权限类型 | 0=私有 1=公开 2=自定义 |

### 3.2 OssClient 核心方法

| 方法 | 功能 |
|------|------|
| upload(Path, String, String, String) | 上传本地文件到 S3 |
| upload(InputStream, String, Long, String) | 上传输入流到 S3 |
| uploadSuffix(byte[], String, String) | 上传字节数组（自动拼接路径） |
| download(String, OutputStream, Consumer) | 下载文件到输出流 |
| fileDownload(String) | 下载文件到本地临时路径 |
| delete(String) | 删除云存储对象 |
| createPresignedGetUrl(String, Duration) | 创建预签名下载 URL |
| createPresignedPutUrl(String, Duration, Map) | 创建预签名上传 URL |
| getPath(String, String) | 生成唯一文件路径（日期+UUID） |

### 3.3 文件路径生成规则

```
格式：prefix/yyyy/MM/dd/uuid.suffix
示例：uploads/2026/05/17/abc123def456.jpg
```

### 3.4 OssFactory 工厂模式

**设计亮点：**
1. **单例缓存**: ConcurrentHashMap 避免重复创建
2. **双重检查锁**: ReentrantLock 保证线程安全
3. **配置热更新**: 检测配置变化自动重建
4. **多租户隔离**: tenantId:configKey 作为缓存键

---

## 四、业务层实现

### 4.1 文件上传流程

```java
1. 校验文件非空
2. 获取原始文件名和后缀
3. OssFactory.instance() 获取默认客户端
4. storage.uploadSuffix() 上传到云存储
5. 构建扩展信息（文件大小、类型）
6. 保存文件信息到 sys_oss 表
7. 返回文件 URL
```

### 4.2 私有桶 URL 处理

- 私有桶文件通过**预签名 URL** 访问
- 默认有效期 **120 秒**
- 查询时自动调用 matchingUrl() 转换

### 4.3 API 接口

| 接口 | 方法 | 路径 | 权限 |
|------|------|------|------|
| 查询列表 | GET | /resource/oss/list | system:oss:list |
| 批量查询 | GET | /resource/oss/listByIds/{ossIds} | system:oss:query |
| 上传文件 | POST | /resource/oss/upload | system:oss:upload |
| 下载文件 | GET | /resource/oss/download/{ossId} | system:oss:download |
| 删除文件 | DELETE | /resource/oss/{ossIds} | system:oss:remove |

---

## 五、数据库设计

### 5.1 sys_oss（文件存储表）

| 字段 | 类型 | 说明 |
|------|------|------|
| oss_id | BIGINT | 主键 ID |
| file_name | VARCHAR(255) | S3 对象 key |
| original_name | VARCHAR(255) | 原始文件名 |
| file_suffix | VARCHAR(20) | 文件后缀 |
| url | VARCHAR(500) | 访问 URL |
| ext1 | TEXT | 扩展信息（JSON） |
| service | VARCHAR(50) | 配置 key |

### 5.2 sys_oss_config（存储配置表）

| 字段 | 类型 | 说明 |
|------|------|------|
| oss_config_id | BIGINT | 主键 ID |
| config_key | VARCHAR(20) | 配置唯一标识 |
| access_key | VARCHAR(255) | 访问密钥 ID |
| secret_key | VARCHAR(255) | 访问密钥 Secret |
| bucket_name | VARCHAR(255) | 存储桶名称 |
| endpoint | VARCHAR(255) | 服务地址 |
| domain | VARCHAR(255) | 自定义域名 |
| is_https | CHAR(1) | 是否 HTTPS |
| access_policy | CHAR(1) | 桶权限类型 |

---

## 六、安全与优化

### 6.1 安全机制

- 预签名 URL（私有桶 120 秒有效）
- Sa-Token 权限注解控制
- 文件类型校验
- 文件大小限制（10MB）

### 6.2 性能优化

- 基于 Netty 的异步非阻塞 I/O
- S3 Transfer Manager 优化大文件传输
- ConcurrentHashMap 缓存客户端实例
- Caffeine + Redis 缓存文件元数据
- 连接池：maxConcurrency=100

---

## 七、使用示例

### 7.1 直接使用 OssClient

```java
OssClient client = OssFactory.instance();
UploadResult result = client.uploadSuffix(
    file.getBytes(), ".jpg", "image/jpeg");
String url = result.getUrl();
```

### 7.2 多配置切换

```java
// 使用指定配置
OssClient client = OssFactory.instance("aliyun-config-2");
```

---

## 八、常见问题

### Q1: MinIO 访问报 403？
- 检查 forcePathStyle 是否启用
- 确认 endpoint 不带协议前缀

### Q2: 私有桶图片无法显示？
- 确认 matchingUrl() 被调用
- 检查预签名 URL 是否过期

### Q3: 多租户如何隔离？
- 配置表设置 tenant_id
- 工厂类使用 tenantId:configKey 作为缓存键