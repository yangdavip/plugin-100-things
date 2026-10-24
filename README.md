# 100 件小事 · Halo 插件

> 短暂人生，你想做的 100 件事情。

一个为 Halo 博客设计的「人生小事清单」插件：内置 100 件想做成的小事，支持打卡、记录心情与一句话备忘，
并在博客前台提供**只读**的清单展示页（`/plugin-100-things`）。

清单条目（编号 / 名字 / 分类）与生活章节（章节名、寄语、解锁阈值）都在控制台里增删改，
前台对访客只读——编辑只发生在控制台，并由后端校验兜底。

- **应用市场**：[待上架](https://www.halo.run/uc/developer/apps)
- **兼容 Halo**：`>=2.27.0`
- **技术栈**：Java 21 + Spring Boot（Reactor / WebFlux）+ Vue 3 + TypeScript

## 功能

### 管理端（Console）

插件在 Console 中新增一个「内容 → 100 件小事」页面，默认打开的是**配置页**：

- **配置（默认页签）**：页面标题、副标题、引言、页脚签名，以及「显示进度条」「按分类分组」两个开关。
  配置存在插件自己的 ConfigMap（`plugin-100-things-config`）里，保存后前台下一次请求即生效，
  **不需要重启插件，也不需要重新发版**。配置页同时给出前台地址与「打开前台」入口。
- **小事清单**：按十个分类分组展示卡片，每张显示编号、小事名、状态胶囊、完成日期与照片标记；
  支持按小事名 / 一句话记录 / 编号搜索，并可按分类筛选。
  - **新增**：工具栏「＋ 新增小事」，编号默认取现有最大编号 + 1（1-999，不可重复）。
  - **编辑**：点开任意卡片，可改编号、名字、分类、状态（想去做 / 等待发生 / 已经珍藏 / 留给以后）、
    完成日期、心情（10 种）与一句话记录；保存时只提交发生变化的字段。
  - **删除**：面板左下角「删除」，需要点两次确认；删除后该编号会释放。
- **生活章节**：章节进度视图（Lv.1 → Lv.6，默认阈值 0 / 20 / 40 / 60 / 80 / 100 件）、已珍藏件数、
  最近心情 Top3、人生关键词云、写给现在的自己、最近珍藏时间线；页面底部「章节设置」可增删改章节名、
  寄语与「攒到多少件才解锁」，保存后前台进度条立刻跟着变。

> **编辑的唯一入口是控制台**：博客前台没有任何写入接口，访客只能看。
> 后端会校验编号范围（1-999）、编号唯一、名字与分类非空——空分类的条目会被前台的
> 分组逻辑丢弃，所以宁可报 `400` 也不要静默消失。

### 博客前台

插件提供前台页面 **`/plugin-100-things`**（即「博客域名/plugin-100-things」），
以奶油纸感手账风格展示清单与已珍藏进度，对访客只读。

旧版本用过的 `/small-things` 会 **302 跳转**到新地址，已有的链接不会变成 404。

前台默认使用插件自带的模板（`templates/small-things.html`）；若当前主题提供了同名的
`small-things.html` 模板，则**优先使用主题的模板**，主题可以完全接管展示样式。

模板可用的数据契约：

| 变量 | 说明 |
| --- | --- |
| `settings` | 展示配置（`pageTitle` / `pageSubtitle` / `pageQuote` / `footerNote` / `showProgress` / `groupByCategory` / `chapters`） |
| `groups` | 已按分类分好组的小事，元素为 `{ category, items }`；分类顺序固定，空分类不出现。关闭「按分类分组」时只有一组、`category` 为 `null` |
| `smallThings` | 小事列表（`SmallThing` 扩展对象，未分组） |
| `treasuredCount` | 已珍藏件数 |
| `chapter` | 当前所处章节（`{ name, say, min }`，来自配置；章节为空时 `null`） |
| `nextChapter` | 下一个未解锁章节（已全部解锁时 `null`） |
| `remaining` | 距下一章还差多少件 |
| `_templateId` | 固定为 `plugin:plugin-100-things:small-things` |

主题侧若提供 `templates/layout.html` 并符合页面布局契约，插件页面会自动复用主题的页头与页脚；
否则回退到 Halo 内置布局。

### 写自定义模板时的两个坑

自己写 `small-things.html` 时请直接用 `groups` 遍历，**不要**在模板里筛分类：

- `${smallThings.?[spec.category == cat]}` —— SpEL 集合选择取不到外层 `th:each` 的循环变量，会抛
  `EL1008E`；而同一元素上 `th:if`（优先级 300）先于 `th:with`（600）求值，异常会被吞成
  「整页只剩标题、一条记录都没有」。
- `spec.status` / `spec.mood` 是**枚举**，写成 `${item.spec.status == 'TREASURED'}` 恒为 false，
  页面不报错，只是所有卡片都显示成「想去做」。请用 `${item.spec.status?.name() == 'TREASURED'}`。

## 安装

### 应用市场

在 Halo 后台「插件 → 获取插件」中搜索「100 件小事」并安装。

### 手动安装

下载本仓库 Release 中的 `plugin-plugin-100-things-*.jar`，在 Halo 后台「插件 → 安装插件 → 上传插件」
中上传该 JAR。

安装后请在「插件」列表中启用插件。**首次启用时插件会自动写入 100 件小事的预置清单**；
之后停用再启用都不会重复写入，也不会把你删掉的条目偷偷补回来（预置数据只在一条都没有时写入）。

## 使用

1. 进入 Console 的「内容 → 100 件小事」，默认落在**配置**页签：按需改标题、副标题、引言、页脚，
   以及是否显示进度条、是否按分类分组，保存即可。
2. 切到「小事清单」，逐条点击卡片，把完成的那些标记为「已经珍藏」，并写下完成日期、当时的心情和一句话记录；
   也可以「＋ 新增小事」加自己的条目，或用「删除」去掉不想要的条目。
3. 切到「生活章节」查看进度，并在底部「章节设置」里改章节名、寄语与解锁阈值。
4. 博客访客可以访问 `https://你的站点/plugin-100-things` 查看清单（只读，免登录，没有任何编辑入口）。

## 权限与角色

插件在「系统 → 用户角色」中提供两个角色模板：

| 角色 | 权限 |
| --- | --- |
| 查看小事清单 | 读取小事与配置 |
| 管理小事清单 | 增删改查小事、读写章节配置（依赖「查看小事清单」） |

前台只读接口通过匿名聚合角色开放，仅包含 `get` / `list`；所有写操作都需要登录并具备管理角色。

## 数据存储

小事保存在 Halo 的自定义模型 `SmallThing`（`hundredthings.plugin.halo.run/v1alpha1`）中，
随 Halo 一起备份与迁移。插件停用**不会**删除这些数据。

页面文案与生活章节存在 ConfigMap `plugin-100-things-config` 里：文案与开关是「一个字段一个键」，
章节因为是多值，序列化成 JSON 放在 `chapters` 键下（读到坏 JSON 只回退默认章节，不会让前台整页报错）。

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
├── SmallThingService.java          # 业务逻辑：列表、增删改、编号唯一性校验、分组
├── SmallThingConfig.java           # 配置模型（文案 + 两个开关 + 生活章节）
├── SmallThingConfigService.java    # 配置读写：ConfigMap plugin-100-things-config
├── SmallThingReconciler.java       # 调谐器：幂等维护 status 与 label
├── SmallThingInitializer.java      # 首次启用写入 100 条预置数据（一条都没有时才写）
├── SmallThingConsoleEndpoint.java  # Console 端 API（清单增删改 + 配置，需登录）
├── SmallThingPublicEndpoint.java   # 前台公开 API（只读）
└── SmallThingPageRouter.java       # 博客前台路由 /plugin-100-things（旧路径 302）
src/main/resources/
├── plugin.yaml                     # 插件清单
├── extensions/                     # 角色模板
├── seed/small-things.json          # 100 条预置数据
└── templates/small-things.html     # 前台默认模板
ui/                                 # Vue 3 + TypeScript 管理端
```

## 许可

[GNU General Public License v3.0](./LICENSE) © 2026 矩阵生长（Matrix Growth）
