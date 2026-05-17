# SMS 短信服务模块分析

## 一、模块概述

RuoYi-Vue-Plus 的短信服务基于 **sms4j** 框架实现，支持数十种短信服务商的统一接入。

### 1.1 支持的短信厂商

| 厂商 | 配置标识 | 说明 |
|------|----------|------|
| 阿里云短信 | alibaba | 阿里云短信服务 |
| 腾讯云短信 | tencent | 腾讯云短信服务 |
| 云片 | yunpian | 云片短信服务 |
| 华为云 | huawei | 华为云短信服务 |
| 百度云 | baidu | 百度云短信服务 |
| 其他 | 更多 | 支持数十种厂商 |

### 1.2 技术栈

- **sms4j v3.3.5**（短信融合框架）
- **Redis**（缓存拦截计数、重试信息）

---

## 二、架构设计

### 2.1 目录结构

```
ruoyi-common-sms/
├── config/SmsAutoConfiguration.java    # 自动配置类
├── core/dao/PlusSmsDao.java            # Redis 缓存 DAO
└── handler/SmsExceptionHandler.java    # 异常处理器
```

### 2.2 核心流程

```
Controller → SmsFactory.getSmsBlend(configKey) → smsBlend.sendMessage() → 短信厂商
```

---

## 三、核心组件详解

### 3.1 SmsAutoConfiguration（自动配置）

```java
@AutoConfiguration(after = {RedisAutoConfiguration.class})
public class SmsAutoConfiguration {
    
    @Primary
    @Bean
    public SmsDao smsDao() {
        return new PlusSmsDao();  // 使用 Redis 缓存
    }

    @Bean
    public SmsExceptionHandler smsExceptionHandler() {
        return new SmsExceptionHandler();  // 全局异常处理
    }
}
```

### 3.2 PlusSmsDao（缓存 DAO）

使用框架统一的 RedisUtils 实现：

| 方法 | 功能 |
|------|------|
| set(key, value, cacheTime) | 存储缓存（带过期时间） |
| set(key, value) | 存储缓存（永久） |
| get(key) | 读取缓存 |
| remove(key) | 删除缓存 |
| clean() | 清空所有短信相关缓存 |

**键前缀**: `GlobalConstants.GLOBAL_REDIS_KEY + "sms:*"`

### 3.3 SmsExceptionHandler（异常处理）

```java
@RestControllerAdvice
public class SmsExceptionHandler {
    
    @ExceptionHandler(SmsBlendException.class)
    public R<Void> handleSmsBlendException(SmsBlendException e, HttpServletRequest request) {
        log.error("请求地址'{}',发生sms短信异常.", requestURI, e);
        return R.fail("短信发送失败，请稍后再试...");
    }
}
```

---

## 四、配置管理

### 4.1 YAML 配置示例

```yaml
sms:
  config-type: yaml           # 配置来源（yaml/interface）
  restricted: true            # 开启短信拦截
  minute-max: 1               # 单手机号每分钟最大发送
  account-max: 30             # 单手机号每日最大发送
  blends:
    config1:                  # 配置 ID（自定义）
      supplier: alibaba       # 厂商标识
      access-key-id: 您的accessKey
      access-key-secret: 您的accessKeySecret
      signature: 您的短信签名
      sdk-app-id: 您的sdkAppId
    config2:
      supplier: tencent
      access-key-id: 您的accessKey
      access-key-secret: 您的accessKeySecret
      signature: 您的短信签名
      sdk-app-id: 您的sdkAppId
```

### 4.2 配置说明

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| config-type | 配置来源类型 | yaml |
| restricted | 是否开启拦截 | true |
| minute-max | 每分钟最大发送 | 1 |
| account-max | 每日最大发送 | 30 |
| blends | 多配置集合 | - |

### 4.3 多配置支持

- 支持同时配置多个同厂商账号（如 ali1, ali2）
- 通过 configKey 区分不同账号
- 支持多租户隔离

---

## 五、使用示例

### 5.1 发送短信验证码

```java
// 1. 获取短信服务实例
SmsBlend smsBlend = SmsFactory.getSmsBlend("config1");

// 2. 构建模板变量
LinkedHashMap<String, String> map = new LinkedHashMap<>();
map.put("code", "1234");  // 验证码

// 3. 发送短信
SmsResponse response = smsBlend.sendMessage(phone, templateId, map);

// 4. 检查结果
if (!response.isSuccess()) {
    // 处理失败
}
```

### 5.2 实际应用场景

#### 短信验证码登录（CaptchaController）

```java
@RateLimiter(key = "#phonenumber", time = 60, count = 1)
@GetMapping("/resource/sms/code")
public R<Void> smsCode(String phonenumber) {
    // 1. 生成 4 位随机验证码
    String code = RandomUtil.randomNumbers(4);
    
    // 2. 存储到 Redis（5分钟有效）
    RedisUtils.setCacheObject(key, code, Duration.ofMinutes(5));
    
    // 3. 发送短信
    SmsBlend smsBlend = SmsFactory.getSmsBlend("config1");
    SmsResponse response = smsBlend.sendMessage(phonenumber, templateId, map);
    
    return response.isSuccess() ? R.ok() : R.fail(response.getData().toString());
}
```

#### 多厂商短信发送（SmsController demo）

```java
// 阿里云短信
SmsBlend aliyun = SmsFactory.getSmsBlend("config1");
aliyun.sendMessage(phone, templateId, map);

// 腾讯云短信
SmsBlend tencent = SmsFactory.getSmsBlend("config2");
tencent.sendMessage(phone, templateId, map);
```

### 5.3 黑名单管理

```java
// 添加黑名单
smsBlend.joinInBlacklist(phone);

// 移除黑名单
smsBlend.removeFromBlacklist(phone);
```

---

## 六、API 接口

### 6.1 短信验证码

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 发送验证码 | GET | /resource/sms/code | 限流：60秒1次 |

### 6.2 Demo 示例接口

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 阿里云发送 | GET | /demo/sms/sendAliyun | 阿里云短信示例 |
| 腾讯云发送 | GET | /demo/sms/sendTencent | 腾讯云短信示例 |
| 添加黑名单 | GET | /demo/sms/addBlacklist | 添加黑名单 |
| 移除黑名单 | GET | /demo/sms/removeBlacklist | 移除黑名单 |

---

## 七、安全机制

### 7.1 短信拦截

| 限制项 | 说明 | 默认值 |
|--------|------|--------|
| 每分钟限制 | 单手机号每分钟最大发送数 | 1 |
| 每日限制 | 单手机号每日最大发送数 | 30 |
| 黑名单 | 禁止发送的手机号列表 | - |

### 7.2 频率限制

```java
// 使用 @RateLimiter 注解限流
@RateLimiter(key = "#phonenumber", time = 60, count = 1)
public R<Void> smsCode(String phonenumber) { ... }
```

### 7.3 验证码安全

- 4 位随机数字验证码
- 5 分钟有效期
- 存储于 Redis 自动过期

---

## 八、数据库设计

短信模块主要依赖 YAML 配置，无需数据库表。

如需记录发送日志，可自行扩展：

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 ID |
| phone | VARCHAR(20) | 手机号 |
| content | TEXT | 短信内容 |
| template_id | VARCHAR(50) | 模板 ID |
| config_key | VARCHAR(50) | 配置 key |
| status | CHAR(1) | 发送状态 |
| response | TEXT | 厂商响应（JSON） |
| create_time | DATETIME | 发送时间 |

---

## 九、扩展开发

### 9.1 添加新的短信厂商

1. 在 YAML 中添加新配置
2. 设置 supplier 为对应厂商标识
3. 填写 accessKey、secretKey 等参数

### 9.2 自定义短信发送服务

```java
@Service
public class MySmsService {
    
    public void sendVerifyCode(String phone) {
        String code = RandomUtil.randomNumbers(4);
        SmsBlend sms = SmsFactory.getSmsBlend("config1");
        sms.sendMessage(phone, "TEMPLATE_ID", Map.of("code", code));
    }
}
```

### 9.3 多租户支持

```yaml
blends:
  tenant1_config:
    tenant-id: "000001"    # 租户 ID
    supplier: alibaba
    # ...
  tenant2_config:
    tenant-id: "000002"
    supplier: tencent
    # ...
```

---

## 十、常见问题

### Q1: 短信发送失败？
- 检查 accessKey/secretKey 是否正确
- 确认短信签名和模板 ID 已审核通过
- 查看日志中的厂商响应信息

### Q2: 如何切换短信账号？
- 修改 SmsFactory.getSmsBlend("configKey") 中的 configKey
- 或在 Redis 中修改默认配置 key

### Q3: 如何关闭短信拦截？
- 设置 `sms.restricted: false`

### Q4: 支持哪些模板变量格式？
- 阿里云：`{"code": "1234"}`
- 腾讯云：`{"1": "1234"}`（数字索引）

---

## 十一、OSS 与 SMS 对比

| 特性 | OSS | SMS |
|------|-----|-----|
| 核心 SDK | AWS S3 SDK v2 | sms4j |
| 支持厂商 | S3 兼容厂商 | 数十种短信厂商 |
| 配置方式 | 数据库动态配置 | YAML 配置 |
| 多账号支持 | ✅（configKey） | ✅（blends） |
| 缓存机制 | Redis + Caffeine | Redis |
| 多租户 | ✅ | ✅ |
| 拦截/限流 | - | ✅（内置） |
| 文件操作 | 上传/下载/删除 | 发送短信 |