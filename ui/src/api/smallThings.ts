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

/** 六个生活章节，阈值对应已珍藏 0 / 20 / 40 / 60 / 80 / 100 件。 */
export const CHAPTERS = [
  { lv: 1, name: '第一章 · 启程', say: '你刚刚把这张清单铺开。', min: 0 },
  { lv: 2, name: '第二章 · 相遇', say: '你开始遇见喜欢的人和事。', min: 20 },
  { lv: 3, name: '第三章 · 生长', say: '你正在长成更完整的自己。', min: 40 },
  { lv: 4, name: '第四章 · 远行', say: '你走出了熟悉的生活半径。', min: 60 },
  { lv: 5, name: '第五章 · 珍藏', say: '你的人生已经攒下许多故事。', min: 80 },
  { lv: 6, name: '终章 · 满载而归', say: '这一段人生，值得被好好记住。', min: 100 },
]

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

/** 创建一条小事。 */
export function createSmallThing(
  spec: SmallThing['spec']
): Promise<SmallThing> {
  return consoleApi<SmallThing>('/smallthings', {
    method: 'post',
    data: { spec },
  })
}

/** 删除一条小事。 */
export function deleteSmallThing(name: string): Promise<SmallThing> {
  return consoleApi<SmallThing>(`/smallthings/${name}`, { method: 'delete' })
}

/** 统计响应。 */
export interface StatsResponse {
  treasuredCount: number
}

/** 获取已珍藏件数。 */
export function fetchStats(): Promise<StatsResponse> {
  return consoleApi<StatsResponse>('/smallthings/stats')
}
