# 100 件小事 · Halo 插件

> 短暂人生，你想做的 100 件事情。

一个为 Halo 博客设计的「人生小事清单」插件：内置 100 件想做成的小事，支持打卡、记录心情与一句话备忘，
并在博客前台提供只读的清单展示页。

- **应用市场**：[待上架](https://www.halo.run/uc/developer/apps)
- **兼容 Halo**：`>=2.27.0`
- **技术栈**：Java 21 + Spring Boot（Reactor / WebFlux）+ Vue 3 + TypeScript

## 功能

### 管理端（Console）

插件在 Console 中新增一个「内容 → 100 件小事」页面：

- **100 件小事卡片墙**：按十个分类分组展示 100 张卡片，每张显示编号、小事名、状态胶囊、完成日期与照片标记。
- **搜索与筛选**：支持按小事名、一句话记录、编号做关键词搜索，并可按分类下拉筛选。
- **记录面板**：点击任意卡片即可打开面板，修改状态（想去做 / 等待发生 / 已经珍藏 / 留给以后）、完成日期、
  心情（10 种）、一句话记录；保存时**只提交发生变化的字段**，其余字段保持原值。
- **我的生活章节**：Lv.1 → Lv.6 六个章节（阈值 0 / 20 / 40 / 60 / 80 / 100 件）的进度线，
  以及已珍藏件数、最近心情 Top3、人生关键词云、写给现在的自己、最近珍藏时间线。
- **顶部实时进度条**：已珍藏 n/100、当前章节等级、距下一章还差多少件。

### 博客前台

插件提供前台页面 `/small-things`，以奶油纸感手账风格展示清单与已珍藏进度。

前台默认使用插件自带的模板（`templates/small-things.html`）；若当前主题提供了同名的
`small-things.html` 模板，则**优先使用主题的模板**，主题可以完全接管展示样式。

模板可用的数据契约：

| 变量 | 说明 |
| --- | --- |
| `smallThings` | 小事列表（`SmallThing` 扩展对象） |
| `treasuredCount` | 已珍藏件数 |
| `_templateId` | 固定为 `plugin:plugin-100-things:small-things` |

主题侧若提供 `templates/layout.html` 并符合页面布局契约，插件页面会自动复用主题的页头与页脚；
否则回退到 Halo 内置布局。

## 安装

### 应用市场

在 Halo 后台「插件 → 获取插件」中搜索「100 件小事」并安装。

### 手动安装

下载本仓库 Release 中的 `plugin-plugin-100-things-*.jar`，在 Halo 后台「插件 → 安装插件 → 上传插件」
中上传该 JAR。

安装后请在「插件」列表中启用插件。**首次启用时插件会自动写入 100 件小事的预置清单**；
之后无论停用再启用多少次，都不会覆盖你已经修改过的记录。

## 使用

1. 进入 Console 的「内容 → 100 件小事」。
2. 逐条点击卡片，把完成的那些标记为「已经珍藏」，并写下完成日期、当时的心情和一句话记录。
3. 切到「我的生活章节」查看进度：已珍藏件数、心情分布、关键词云与最近珍藏时间线。
4. 博客访客可以访问 `https://你的站点/small-things` 查看清单（只读，免登录）。

## 权限与角色

插件在「系统 → 用户角色」中提供两个角色模板：

| 角色 | 权限 |
| --- | --- |
| 查看小事清单 | 读取小事 |
| 管理小事清单 | 增删改查小事（依赖「查看小事清单」） |

前台只读接口通过匿名聚合角色开放，仅包含 `get` / `list`；所有写操作都需要登录并具备管理角色。

## 数据存储

数据保存在 Halo 的自定义模型 `SmallThing`（`hundredthings.plugin.halo.run/v1alpha1`）中，
随 Halo 一起备份与迁移。插件停用**不会**删除这些数据。

## 开发

```bash
# 后端 + UI 一起构建，产出 build/libs/plugin-plugin-100-things-*.jar
./gradlew build

# 只跑后端测试
./gradlew test

# 启动本地 Halo（需要 Docker）
./gradlew haloServer
```

UI 子项目在 `ui/` 目录：

```bash
cd ui
pnpm install
pnpm dev           # 监听构建
pnpm type-check    # 类型检查
pnpm test:unit     # 单元测试
```

> **UI 构建工具**：仓库默认走官方 `pnpm` 流程（与应用市场 CI 保持一致）。
> 若本机环境禁止 pnpm 创建 symlink（部分受限沙箱会拦截），可在 `~/.gradle/gradle.properties`
> 中加一行 `ui.useNpm=true`，或执行 `./gradlew build -Pui.useNpm=true` 临时切到 npm。
> 这个开关只影响本机，不会改变仓库与 CI 的行为。

## 目录结构

```
src/main/java/com/matrixgrowth/plugin100things/
├── Plugin100ThingsPlugin.java     # 插件主类：注册 Scheme + 写入预置数据
├── SmallThing.java                 # 自定义模型（编号/小事/分类/状态/日期/心情/记录/照片）
├── SmallThingService.java          # 业务逻辑：列表、增量更新、删除
├── SmallThingReconciler.java       # 调谐器：幂等维护 status 与 label
├── SmallThingInitializer.java      # 首次启用写入 100 条预置数据
├── SmallThingConsoleEndpoint.java  # Console 端 API（需登录）
├── SmallThingPublicEndpoint.java   # 前台公开 API（只读）
└── SmallThingPageRouter.java       # 博客前台路由 /small-things
src/main/resources/
├── plugin.yaml                     # 插件清单
├── extensions/                     # 角色模板
├── seed/small-things.json          # 100 条预置数据
└── templates/small-things.html     # 前台默认模板
ui/                                 # Vue 3 + TypeScript 管理端
```

## 许可

[GNU General Public License v3.0](./LICENSE) © 2026 矩阵生长（Matrix Growth）
