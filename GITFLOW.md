# XingHui Admin - GitFlow 分支管理规范

## 📋 分支结构

| 分支类型 | 分支名称 | 说明 | 合并目标 |
|---------|---------|------|---------|
| **生产分支** | `master` | 线上生产代码，受保护，禁止直接推送 | - |
| **开发分支** | `dev` | 日常开发集成分支 | → `master` (via release) |
| **功能分支** | `feature/<名称>` | 新功能开发 | → `dev` |
| **修复分支** | `fix/<名称>` | 开发期缺陷修复 | → `dev` |
| **热修复分支** | `hotfix/<名称>` | 紧急线上修复 | → `master` + `dev` |
| **发布分支** | `release/<版本号>` | 版本发布准备 | → `master` + `dev` |

## 🔄 GitFlow 工作流程

### 1. 日常功能开发（feature → dev）

```bash
# 从 dev 创建功能分支
git checkout dev
git pull origin dev
git checkout -b feature/oss-module

# 开发过程中，定期提交（遵循 Conventional Commits）
git add .
git commit -m "feat(oss): 新增分片断点续传"

# 完成后，合并回 dev（--no-ff 保留分支拓扑）
git checkout dev
git pull origin dev
git merge --no-ff feature/oss-module
git push origin dev

# 删除已合并的功能分支
git branch -d feature/oss-module
```

### 2. 版本发布（dev → release → master）

```bash
# 从 dev 创建发布分支
git checkout dev
git checkout -b release/v1.1.0

# 发布分支上仅做 bug 修复与版本号调整，不加新功能

# 合并到 master 并打标签
git checkout master
git merge --no-ff release/v1.1.0
git tag -a v1.1.0 -m "v1.1.0: 版本描述"
git push origin master v1.1.0

# 同步回 dev
git checkout dev
git merge --no-ff release/v1.1.0
git push origin dev

git branch -d release/v1.1.0
```

### 3. 紧急热修复（hotfix → master + dev）

```bash
git checkout master
git checkout -b hotfix/login-npe
# 修复并提交
git commit -m "fix(login): 修复空指针异常"
# 合并 master 打补丁标签，再同步回 dev（流程同 release）
```

## 📝 Commit 提交规范

使用 [Conventional Commits](https://www.conventionalcommits.org/) 格式：

```
<type>(<scope>): <subject>

<body：说明改动动机与优化点，逐条列出>
```

| 类型 | 说明 |
|------|------|
| `feat` | 新功能 |
| `fix` | 修复 Bug |
| `docs` | 文档更新 |
| `refactor` | 重构（不是新功能也不是修复） |
| `perf` | 性能优化 |
| `test` | 测试相关 |
| `chore` | 构建/工具变更 |
| `ci` | CI/CD 配置 |

示例：

```bash
git commit -m "feat(db): 引入 Flyway 数据库版本化迁移"
git commit -m "fix(auth): token 过期返回 401 而非 500"
```

## ⚠️ 硬性约束

1. 禁止直接向 `master` 推送提交，一律经 release / hotfix 分支合入；
2. 合并使用 `--no-ff`，保留功能分支拓扑便于回溯；
3. 已合并进 dev 的 Flyway 迁移脚本不可修改（checksum 校验），写错须新增脚本修正；
4. 提交信息必须写清楚改动点与动机，CI（GitHub Actions）绿色方可合并。
