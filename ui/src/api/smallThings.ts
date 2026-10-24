/**
 * 「100 件小事」的数据模型与 API 客户端。
 *
 * 后端 CustomEndpoint 的 group 分别是：
 * - Console 端：`console.api.hundredthings.plugin.halo.run/v1alpha1`
 * - 前台公开端：`api.hundredthings.plugin.halo.run/v1alpha1`
 *
 * 请求统一走 Halo 宿主提供的 {@link axiosInstance}，以便复用宿主的鉴权、CSRF 与错误处理。
 */
import { axiosInstance } from '@halo-dev/api-client'

/** 小事状态（与后端 SmallThingSpec.Status 一一对应）。 */
export type SmallThingStatus = 'TODO' | 'WAITING' | 'TREASURED' | 'LATER'

/** 心情（与后端 SmallThingSpec.Mood 一一对应）。 */
export type SmallThingMood =
  | 'CALM'
  | 'EXPECTED'
  | 'SURPRISED'
  | 'MOVED'
  | 'RELAXED'
  | 'TIRED'
  | 'CONFUSED'
  | 'MISSING'
  | 'FREE'
  | 'OTHER'

/** 一条小事。 */
export interface SmallThing {
  apiVersion: string
  kind: string
  metadata: {
    name: string
    version?: number
    creationTimestamp?: string
    labels?: Record<string, string>
  }
  spec: {
    number: number
    title: string
    category: string
    status: SmallThingStatus
    mood?: SmallThingMood
    doneAt?: string
    note?: string
    photos?: string[]
    owner?: string
  }
  status: {
    phase?: string
    stage: string
    observedTime: string
  }
}

/** 状态的中文展示文案。 */
export const STATUS_TEXT: Record<SmallThingStatus, string> = {
  TODO: '想去做',
  WAITING: '等待发生',
  TREASURED: '已经珍藏',
  LATER: '留给以后',
}

/** 心情的中文展示文案。 */
export const MOOD_TEXT: Record<SmallThingMood, string> = {
  CALM: '平静',
  EXPECTED: '期待',
  SURPRISED: '惊喜',
  MOVED: '感动',
  RELAXED: '放松',
  TIRED: '疲惫',
  CONFUSED: '迷茫',
  MISSING: '想念',
  FREE: '自由',
  OTHER: '其他',
}

/** 六个生活章节的默认定义（实际值以后端返回的 config.chapters 为准）。 */
export interface Chapter {
  /** 章节短名，如「启程」。 */
  name: string
  /** 章节寄语。 */
  say: string
  /** 解锁该章所需的已珍藏件数。 */
  min: number
}

/** 十个默认分类。 */
export const CATEGORIES = [
  '青春时光',
  '生活日常',
  '朋友之间',
  '一个人的浪漫',
  '小小远方',
  '关于热爱',
  '勇敢一点',
  '感受世界',
  '留下痕迹',
  '好好告别',
]

/** Console 端 API 的 group/version。 */
const CONSOLE_API = '/apis/console.api.hundredthings.plugin.halo.run/v1alpha1'

/**
 * 调用 Console 端 API。
 *
 * @param path 相对路径（不含 /apis 前缀）
 * @param config axios 请求配置
 * @returns 响应数据
 */
async function consoleApi<T>(
  path: string,
  config?: Parameters<typeof axiosInstance.request>[0]
): Promise<T> {
  const response = await axiosInstance.request<T>({
    url: `${CONSOLE_API}${path}`,
    method: 'get',
    ...config,
  })
  return response.data
}

/** 列出全部小事。 */
export function listSmallThings(): Promise<SmallThing[]> {
  return consoleApi<SmallThing[]>('/smallthings')
}

/**
 * 新增一条小事。
 *
 * metadata.name 由后端按编号生成并回传，这里不用（也不该）自己拼。
 */
export function createSmallThing(
  spec: Pick<SmallThing['spec'], 'number' | 'title' | 'category'> &
    Partial<SmallThing['spec']>
): Promise<SmallThing> {
  return consoleApi<SmallThing>('/smallthings', {
    method: 'post',
    data: { spec },
  })
}

/** 删除一条小事。 */
export function deleteSmallThing(name: string): Promise<SmallThing> {
  return consoleApi<SmallThing>(`/smallthings/${name}`, {
    method: 'delete',
  })
}

/** 更新一条小事（增量更新：只传需要变更的字段）。 */
export function updateSmallThing(
  name: string,
  spec: Partial<SmallThing['spec']>
): Promise<SmallThing> {
  return consoleApi<SmallThing>(`/smallthings/${name}`, {
    method: 'put',
    data: { spec },
  })
}

/**
 * 展示配置 + 章节定义。
 *
 * 存在插件自己的 ConfigMap 里，保存后前台下一次请求即生效。
 */
export interface SmallThingSettings {
  pageTitle: string
  pageSubtitle: string
  pageQuote: string
  footerNote: string
  showProgress: boolean
  groupByCategory: boolean
  /** 生活章节（控制台可增删改，前后台都以它为准）。 */
  chapters: Chapter[]
}

/** 配置接口的响应。 */
export interface SettingsResponse {
  /** 前台访问路径，由后端路由常量给出。 */
  pagePath: string
  /** 当前生效的配置。 */
  config: SmallThingSettings
  /** 后端默认配置，供「恢复默认」使用。 */
  defaults: SmallThingSettings
}

/** 读取展示配置。 */
export function fetchSettings(): Promise<SettingsResponse> {
  return consoleApi<SettingsResponse>('/settings')
}

/** 保存展示配置（改完立即生效，不需要重启插件）。 */
export function saveSettings(
  config: SmallThingSettings
): Promise<SettingsResponse> {
  return consoleApi<SettingsResponse>('/settings', {
    method: 'put',
    data: config,
  })
}

/** 统计响应。 */
export interface StatsResponse {
  treasuredCount: number
}

/** 获取已珍藏件数。 */
export function fetchStats(): Promise<StatsResponse> {
  return consoleApi<StatsResponse>('/smallthings/stats')
}
