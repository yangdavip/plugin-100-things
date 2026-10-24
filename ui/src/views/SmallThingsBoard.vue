<script setup lang="ts">
/**
 * 小事清单 / 生活章节。
 *
 * - 清单：新增、编辑（编号 / 名字 / 分类 / 状态 / 日期 / 心情 / 一句话）、删除。
 * - 章节：增删改章节名、寄语与解锁阈值，存进 ConfigMap，前台进度条立刻跟着变。
 *
 * 前台展示页对访客只读；编辑只发生在这里（控制台），并由后端校验兜底。
 */
import { computed, onMounted, ref } from 'vue'
import {
  CATEGORIES,
  MOOD_TEXT,
  STATUS_TEXT,
  createSmallThing,
  deleteSmallThing,
  fetchSettings,
  listSmallThings,
  saveSettings,
  updateSmallThing,
  type Chapter,
  type SmallThing,
  type SmallThingMood,
  type SmallThingSettings,
  type SmallThingStatus,
} from '@/api/smallThings'

const things = ref<SmallThing[]>([])
const settings = ref<SmallThingSettings | null>(null)
const defaults = ref<SmallThingSettings | null>(null)
const loading = ref(true)
const errorMessage = ref('')
const notice = ref('')
const keyword = ref('')
const categoryFilter = ref('')
/** 编辑中的草稿；新增时 editingOriginal 为 null。 */
const editing = ref<SmallThing | null>(null)
const editingOriginal = ref<SmallThing | null>(null)
const saving = ref(false)
const deleting = ref(false)
const confirmDelete = ref(false)
const modalError = ref('')

/** 章节草稿（与 settings.chapters 分开，未保存时不影响已生效配置）。 */
const chapterDraft = ref<Chapter[]>([])
const chapterSaving = ref(false)
const chapterError = ref('')
const chapterNotice = ref('')

/** 是否为新增。 */
const isCreating = computed(() => editingOriginal.value === null)

/**
 * 当前视图（由外层页签控制）。
 *
 * 视图切换放在外层，是因为「配置」本身也是一个页签：如果看板自己再管一份页签状态，
 * 两处都会有一份「当前在哪个视图」的记忆，切换时必然有一边是旧的。
 */
defineProps<{ view: 'list' | 'chapter' }>()

/** 已珍藏件数。 */
const treasuredCount = computed(
  () =>
    things.value.filter((t) => t.spec.status === 'TREASURED').length
)

/** 章节（按阈值升序，与后端排序规则一致）。 */
const chapters = computed<Chapter[]>(() =>
  [...(settings.value?.chapters ?? [])].sort((a, b) => a.min - b.min)
)

/**
 * 分类候选：内置十类 + 数据里出现过的自定义分类。
 *
 * 只给内置十类的话，编辑一条自定义分类的条目时下拉框会显示成空，
 * 用户一保存就把分类改成了别的（静默改数据）。
 */
const categoryOptions = computed(() => {
  const set = new Set<string>(CATEGORIES)
  for (const thing of things.value) {
    if (thing.spec.category) {
      set.add(thing.spec.category)
    }
  }
  const current = editing.value?.spec.category
  if (current) {
    set.add(current)
  }
  return Array.from(set)
})

/** 当前章节（按已珍藏件数取阈值不超过的最大章节）。 */
const currentChapter = computed(() => {
  const list = chapters.value
  if (!list.length) {
    return { name: '尚未设置章节', say: '', min: 0, lv: 1 }
  }
  let index = 0
  list.forEach((chapter, i) => {
    if (treasuredCount.value >= chapter.min) {
      index = i
    }
  })
  return { ...list[index]!, lv: index + 1 }
})

/** 下一个尚未达成的章节。 */
const nextChapter = computed(() =>
  chapters.value.find((c) => treasuredCount.value < c.min)
)

/** 距下一章还差多少件。 */
const remaining = computed(() =>
  nextChapter.value ? nextChapter.value.min - treasuredCount.value : 0
)

/** 按分类分组（保持 CATEGORIES 的固定顺序，未知分类排最后）。 */
const grouped = computed(() => {
  const map = new Map<string, SmallThing[]>()
  for (const thing of things.value) {
    const cat = thing.spec.category || '未分类'
    if (!map.has(cat)) {
      map.set(cat, [])
    }
    map.get(cat)!.push(thing)
  }
  const order = [...CATEGORIES, ...Array.from(map.keys()).filter((k) => !CATEGORIES.includes(k))]
  return order
    .filter((cat) => map.has(cat))
    .map((cat) => ({ cat, items: map.get(cat)! }))
})

/** 搜索 + 分类筛选后的结果。 */
const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return things.value.filter((t) => {
    if (categoryFilter.value && t.spec.category !== categoryFilter.value) {
      return false
    }
    if (!kw) {
      return true
    }
    const hay = `${t.spec.title} ${t.spec.note || ''} ${t.spec.number}`.toLowerCase()
    return hay.includes(kw)
  })
})

/** 筛选后的分组。 */
const filteredGroups = computed(() => {
  const map = new Map<string, SmallThing[]>()
  for (const thing of filtered.value) {
    const cat = thing.spec.category || '未分类'
    if (!map.has(cat)) {
      map.set(cat, [])
    }
    map.get(cat)!.push(thing)
  }
  const order = [...CATEGORIES, ...Array.from(map.keys()).filter((k) => !CATEGORIES.includes(k))]
  return order
    .filter((cat) => map.has(cat))
    .map((cat) => ({ cat, items: map.get(cat)! }))
})

/** 心情 Top3（按已珍藏条目统计）。 */
const topMoods = computed(() => {
  const counter = new Map<SmallThingMood, number>()
  for (const thing of things.value) {
    if (thing.spec.status !== 'TREASURED' || !thing.spec.mood) {
      continue
    }
    counter.set(thing.spec.mood, (counter.get(thing.spec.mood) || 0) + 1)
  }
  return Array.from(counter.entries())
    .sort((a, b) => b[1] - a[1])
    .slice(0, 3)
})

/** 最近珍藏（按完成日期倒序）。 */
const recentTreasured = computed(() =>
  things.value
    .filter((t) => t.spec.status === 'TREASURED')
    .sort((a, b) => (b.spec.doneAt || '').localeCompare(a.spec.doneAt || ''))
    .slice(0, 8)
)

/** 人生关键词云：从标题与记录里统计中文双字词。 */
const keywords = computed(() => {
  const stop = new Set(
    '的了是在有和就不也都你他她它这那些个吗呢吧啊呀哦嗯把被让给对从到为与及但却又再还更最会能可要说过很太非常真的没有什么一二三四五六七八九十'.split(
      ''
    )
  )
  const blocked = new Set([
    '一个', '一次', '自己', '还是', '什么', '可以', '这样', '那样', '只是',
    '没有', '真的', '不是', '就是', '后来', '然后', '已经', '总是', '常常',
    '终于', '大概', '好像', '似乎', '一样', '起来', '下来', '出来', '上去',
    '过去', '那么', '多少', '所有', '东西', '心里', '时候', '地方', '样子',
    '感觉', '知道', '看见', '发现', '翻开', '写字', '字迹', '楼上', '安静得',
  ])
  const counter = new Map<string, number>()
  for (const thing of things.value) {
    const text = `${thing.spec.title} ${thing.spec.note || ''}`
    for (const run of text.match(/[\u4e00-\u9fa5]+/g) || []) {
      for (let i = 0; i + 2 <= run.length; i++) {
        const word = run.slice(i, i + 2)
        if (blocked.has(word)) continue
        const a = word[0] ?? ''
        const b = word[1] ?? ''
        if (!a || !b) continue
        if (stop.has(a) || stop.has(b)) continue
        if (a === b) continue
        counter.set(word, (counter.get(word) || 0) + 1)
      }
    }
  }
  return Array.from(counter.entries())
    .filter(([, count]) => count >= 2)
    .sort((a, b) => b[1] - a[1])
    .slice(0, 14)
})

/** 写给现在的自己。 */
const letter = computed(() => {
  const n = treasuredCount.value
  let text: string
  if (n === 0) {
    text = '这 100 件小事还没有被划掉任何一件。别急，第一件小事往往最难开始。挑一个今天就能做的，把它写下来。'
  } else if (nextChapter.value) {
    text = `你已经认真完成了 ${n} 件小事。它们散落在不同的日子里，可能你自己都记不清了。再攒 ${remaining.value} 件，你就走到「${nextChapter.value.name}」了。日子不急，小事也不急，慢慢来。`
  } else {
    text = '100 件小事，全部被一一收藏。你走完了这张清单，也走完了一段人生。别回头看，看前面就好。'
  }
  const latest = recentTreasured.value[0]
  if (latest && latest.spec.note) {
    text += `　你最近写下的那句话是：「${latest.spec.note}」。`
  }
  return text
})

/** 加载数据（清单 + 配置里的章节）。 */
async function load() {
  loading.value = true
  errorMessage.value = ''
  try {
    // 两个请求一起发：章节列表和清单没有先后依赖，串行会让首屏多等一个来回
    const [items, response] = await Promise.all([listSmallThings(), fetchSettings()])
    things.value = items
    settings.value = response.config
    defaults.value = response.defaults
    chapterDraft.value = structuredClone(response.config.chapters)
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

/** 打开记录面板。 */
function openEditor(thing: SmallThing) {
  // ⚠ 必须深拷贝。直接引用列表里的对象时，编辑器每敲一个字就落到 things 上，
  // 保存前那句「和原值比有没有变化」永远相等 ⟹ 保存变成静默空操作，
  // 界面还会显示「已保存」（真实缺陷，不是理论风险）。
  editingOriginal.value = thing
  editing.value = structuredClone(thing)
  confirmDelete.value = false
  modalError.value = ''
}

/** 打开新增面板（编号默认取现有最大编号 + 1）。 */
function openCreator() {
  const maxNumber = things.value.reduce((max, t) => Math.max(max, t.spec.number), 0)
  editingOriginal.value = null
  editing.value = {
    apiVersion: '',
    kind: '',
    metadata: { name: '' },
    spec: {
      number: Math.min(999, maxNumber + 1),
      title: '',
      category: CATEGORIES[0]!,
      status: 'TODO',
    },
    status: { stage: 'TRACKED', observedTime: '' },
  }
  confirmDelete.value = false
  modalError.value = ''
}

/** 关闭记录面板。 */
function closeEditor() {
  editing.value = null
  editingOriginal.value = null
  confirmDelete.value = false
  modalError.value = ''
}

/** 保存（新增走 POST，编辑走增量 PUT）。 */
async function saveEditor() {
  const draft = editing.value
  if (!draft || saving.value) {
    return
  }
  const original = editingOriginal.value
  const number = Number(draft.spec.number)
  // 前端先挡一遍，避免为了看一条错误提示跑一趟网络往返；后端仍然会校验
  if (!draft.spec.title.trim()) {
    modalError.value = '请填写这件小事的名字'
    return
  }
  if (!draft.spec.category) {
    modalError.value = '请选择分类（空分类的条目不会出现在前台）'
    return
  }
  if (!Number.isInteger(number) || number < 1 || number > 999) {
    modalError.value = '编号必须是 1-999 之间的整数'
    return
  }
  saving.value = true
  modalError.value = ''
  try {
    if (!original) {
      await createSmallThing({
        number,
        title: draft.spec.title.trim(),
        category: draft.spec.category,
        status: draft.spec.status,
        mood: draft.spec.mood,
        doneAt: draft.spec.doneAt || undefined,
        note: draft.spec.note || undefined,
      })
      notice.value = `已新增 № ${pad3(number)}`
    } else {
      const spec: Partial<SmallThing['spec']> = {}
      if (number !== original.spec.number) {
        spec.number = number
      }
      if (draft.spec.title.trim() !== original.spec.title) {
        spec.title = draft.spec.title.trim()
      }
      if (draft.spec.category !== original.spec.category) {
        spec.category = draft.spec.category
      }
      if (draft.spec.status !== original.spec.status) {
        spec.status = draft.spec.status
      }
      if ((draft.spec.doneAt || '') !== (original.spec.doneAt || '')) {
        spec.doneAt = draft.spec.doneAt
      }
      if ((draft.spec.mood || '') !== (original.spec.mood || '')) {
        spec.mood = draft.spec.mood
      }
      if ((draft.spec.note || '') !== (original.spec.note || '')) {
        spec.note = draft.spec.note
      }
      if (Object.keys(spec).length) {
        await updateSmallThing(draft.metadata.name, spec)
        notice.value = '已保存，前台刷新后生效'
      }
    }
    await load()
    closeEditor()
  } catch (e) {
    // 错误显示在面板里：抛到页面顶部时，弹层把提示挡住了，用户只能看到「什么都没发生」
    modalError.value = e instanceof Error ? e.message : String(e)
  } finally {
    saving.value = false
  }
}

/** 删除（需要点两次：第一下变成「确认删除」，避免误触）。 */
async function removeThing() {
  const draft = editing.value
  if (!draft || !editingOriginal.value || deleting.value) {
    return
  }
  if (!confirmDelete.value) {
    confirmDelete.value = true
    return
  }
  deleting.value = true
  modalError.value = ''
  try {
    await deleteSmallThing(draft.metadata.name)
    notice.value = `已删除 № ${pad3(draft.spec.number)}`
    await load()
    closeEditor()
  } catch (e) {
    modalError.value = e instanceof Error ? e.message : String(e)
    confirmDelete.value = false
  } finally {
    deleting.value = false
  }
}

/** 新增一个章节（阈值默认排在最后一个之后）。 */
function addChapter() {
  const maxMin = chapterDraft.value.reduce((max, c) => Math.max(max, c.min), 0)
  chapterDraft.value.push({ name: '', say: '', min: Math.min(999, maxMin + 10) })
  chapterNotice.value = ''
}

/** 删除一个章节。 */
function removeChapter(index: number) {
  chapterDraft.value.splice(index, 1)
  chapterNotice.value = ''
}

/** 章节填回默认值（仍需点保存才落库）。 */
function resetChapters() {
  if (!defaults.value) {
    return
  }
  chapterDraft.value = structuredClone(defaults.value.chapters)
  chapterNotice.value = '已填回默认章节，点「保存章节」后生效'
}

/** 保存章节（连同当前页面配置一起写回 ConfigMap）。 */
async function saveChapters() {
  if (!settings.value || chapterSaving.value) {
    return
  }
  chapterSaving.value = true
  chapterError.value = ''
  chapterNotice.value = ''
  try {
    const response = await saveSettings({
      ...settings.value,
      chapters: chapterDraft.value,
    })
    // 以服务端规范化后的结果为准：它会去重阈值、给空名字补默认名
    settings.value = response.config
    defaults.value = response.defaults
    chapterDraft.value = structuredClone(response.config.chapters)
    chapterNotice.value = '章节已保存，前台刷新即可看到'
  } catch (e) {
    chapterError.value = e instanceof Error ? e.message : String(e)
  } finally {
    chapterSaving.value = false
  }
}

/** 格式化日期为 25.03.08。 */
function fmtDate(value?: string) {
  if (!value) {
    return ''
  }
  const [y, m, d] = value.slice(0, 10).split('-')
  if (!y || !m || !d) {
    return value.slice(0, 10)
  }
  return `${y.slice(2)}.${m}.${d}`
}

/** 补零到三位。 */
function pad3(n: number) {
  return String(n).padStart(3, '0')
}

onMounted(load)
</script>

<template>
  <div class="th-workbench">
    <!-- 顶部进度条 -->
    <header class="th-header">
      <div class="th-brand serif">100 件小事 · 人生手账</div>
      <div class="th-prog">
        <span class="th-prog-text">
          已珍藏 <b>{{ treasuredCount }}</b> / 100 件
        </span>
        <div class="th-track">
          <div class="th-fill" :style="{ width: `${treasuredCount}%` }" />
        </div>
        <span class="th-lv">Lv.{{ currentChapter.lv }} {{ currentChapter.name }}</span>
        <span class="th-prog-text">
          {{ nextChapter ? `距${nextChapter.name}还差 ${remaining} 件` : '已抵达终章' }}
        </span>
      </div>
    </header>

    <div v-if="errorMessage" class="th-error">
      <span>{{ errorMessage }}</span>
      <button @click="load">重试</button>
    </div>

    <div v-if="loading" class="th-state">正在读取小事清单…</div>

    <!-- ============ 卡片墙 ============ -->
    <section v-else-if="view === 'list'">
      <div class="th-head">
        <h1 class="serif">短暂人生，你想做的 100 件事情</h1>
        <div class="th-sub">一百件小事 · 一生的清单</div>
        <div class="th-quote">有些事情不是为了完成，而是为了记住。</div>
        <div class="th-lock">
          前台访客只读；这里的增删改会立即反映到前台页面。
        </div>
      </div>

      <div class="th-tools">
        <input
          v-model="keyword"
          class="th-search"
          type="text"
          placeholder="搜索一件小事、一句话记录…"
        />
        <select v-model="categoryFilter" class="th-filter">
          <option value="">全部分类</option>
          <option v-for="cat in CATEGORIES" :key="cat" :value="cat">{{ cat }}</option>
        </select>
        <span class="th-count">显示 {{ filtered.length }} / {{ things.length }} 件</span>
        <button type="button" class="th-btn primary" @click="openCreator">＋ 新增小事</button>
      </div>

      <div v-if="notice" class="th-notice">{{ notice }}</div>

      <div v-if="!filteredGroups.length" class="th-state">
        还没有匹配的小事，换个关键词试试。
      </div>

      <section v-for="group in filteredGroups" :key="group.cat" class="th-group">
        <div class="th-group-head">
          <h2 class="serif">{{ group.cat }}</h2>
          <div class="th-rule" />
          <div class="th-gnum">{{ group.items.length }} 件</div>
        </div>
        <div class="th-grid">
          <article
            v-for="thing in group.items"
            :key="thing.metadata.name"
            class="th-card"
            @click="openEditor(thing)"
          >
            <span class="th-card-no">№ {{ pad3(thing.spec.number) }}</span>
            <span v-if="thing.spec.photos?.length" class="th-photo">📷</span>
            <div class="th-card-name">{{ thing.spec.title }}</div>
            <div class="th-card-foot">
              <span class="th-pill" :class="thing.spec.status.toLowerCase()">
                {{ STATUS_TEXT[thing.spec.status] }}
              </span>
              <span class="th-card-date">{{ fmtDate(thing.spec.doneAt) }}</span>
            </div>
          </article>
        </div>
      </section>
    </section>

    <!-- ============ 生活章节 ============ -->
    <section v-else>
      <div class="th-head">
        <h1 class="serif">我的生活章节</h1>
        <div class="th-sub">一步一格，都是走过的路</div>
      </div>

      <div class="th-chapter-hero">
        <div class="th-lv-big">Lv.{{ currentChapter.lv }}</div>
        <h2 class="serif">{{ currentChapter.name }}</h2>
        <div class="th-say">{{ currentChapter.say }}</div>
        <div class="th-cnt">已珍藏 <b>{{ treasuredCount }}</b> / 100 件</div>
      </div>

      <div class="th-roadmap">
        <div class="th-road-track">
          <div class="th-road-fill" :style="{ width: `${Math.min(100, treasuredCount)}%` }" />
        </div>
        <div class="th-road-nodes">
          <div
            v-for="(chapter, index) in chapters"
            :key="`${index}-${chapter.min}`"
            class="th-road-node"
            :class="{ on: treasuredCount >= chapter.min }"
          >
            <div class="th-rn-dot" />
            <div class="th-rn-name">{{ chapter.name }}</div>
            <div class="th-rn-min">{{ chapter.min }} 件</div>
          </div>
        </div>
      </div>

      <div class="th-sec-title">章节设置</div>
      <div class="th-ch-edit">
        <p class="th-ch-hint">
          章节名与「攒到多少件才解锁」都在这里改。阈值重复时只会保留靠前的一章（后面那章永远解锁不了）。
        </p>
        <div v-if="chapterError" class="th-modal-error">{{ chapterError }}</div>
        <div v-for="(chapter, index) in chapterDraft" :key="index" class="th-ch-row">
          <span class="th-ch-lv">Lv.{{ index + 1 }}</span>
          <input
            v-model="chapter.name"
            class="th-input th-ch-name"
            type="text"
            maxlength="32"
            placeholder="章节名，如 启程"
          />
          <input
            v-model="chapter.say"
            class="th-input th-ch-say"
            type="text"
            maxlength="120"
            placeholder="这一章的一句话"
          />
          <label class="th-ch-min">
            <input
              v-model.number="chapter.min"
              class="th-input"
              type="number"
              min="0"
              max="999"
            />
            <span>件解锁</span>
          </label>
          <button type="button" class="th-ch-del" @click="removeChapter(index)">删除</button>
        </div>
        <div v-if="!chapterDraft.length" class="th-muted">
          还没有章节。至少留一章，否则前台的进度条会空着。
        </div>
        <div class="th-ch-foot">
          <span v-if="chapterNotice" class="th-ch-notice">{{ chapterNotice }}</span>
          <button type="button" class="th-btn" @click="addChapter">＋ 新增章节</button>
          <button type="button" class="th-btn" @click="resetChapters">恢复默认章节</button>
          <button
            type="button"
            class="th-btn primary"
            :disabled="chapterSaving"
            @click="saveChapters"
          >
            {{ chapterSaving ? '保存中…' : '保存章节' }}
          </button>
        </div>
      </div>

      <div class="th-sec-title">此刻的人生</div>
      <div class="th-stats">
        <div class="th-stat-card">
          <div class="th-stat-t">已经珍藏</div>
          <div class="th-stat-v">{{ treasuredCount }} <small>件小事</small></div>
        </div>
        <div class="th-stat-card">
          <div class="th-stat-t">最近的心情</div>
          <div v-if="!topMoods.length" class="th-muted">还没有记录心情</div>
          <div v-for="[mood, count] in topMoods" :key="mood" class="th-mood-row">
            <span class="th-mn">{{ MOOD_TEXT[mood] }}</span>
            <span class="th-mb"
              ><i :style="{ width: `${(count / (topMoods[0]?.[1] || 1)) * 100}%` }"
            /></span>
            <span class="th-mc">{{ count }}</span>
          </div>
        </div>
        <div class="th-stat-card">
          <div class="th-stat-t">我的人生关键词</div>
          <div v-if="!keywords.length" class="th-muted">还没有关键词</div>
          <div class="th-cloud">
            <span
              v-for="([word, count], index) in keywords"
              :key="word"
              :class="index < 3 ? 'c-lg' : index < 7 ? 'c-md' : 'c-sm'"
            >{{ word }}</span>
          </div>
        </div>
      </div>

      <div class="th-sec-title">写给现在的自己</div>
      <div class="th-letter">
        <p class="serif">{{ letter }}</p>
        <div class="th-sign">—— 此刻的你</div>
      </div>

      <div class="th-sec-title">最近珍藏</div>
      <div class="th-timeline">
        <div v-if="!recentTreasured.length" class="th-state">还没有珍藏的记录</div>
        <div v-for="thing in recentTreasured" :key="thing.metadata.name" class="th-tl-item">
          <div class="th-tl-date">{{ fmtDate(thing.spec.doneAt) }}</div>
          <div class="th-tl-name">
            № {{ pad3(thing.spec.number) }}　{{ thing.spec.title }}
          </div>
          <div v-if="thing.spec.note" class="th-tl-note">{{ thing.spec.note }}</div>
          <span v-if="thing.spec.mood" class="th-tl-mood">
            {{ MOOD_TEXT[thing.spec.mood] }}
          </span>
        </div>
      </div>
    </section>

    <!-- ============ 记录面板 ============ -->
    <div v-if="editing" class="th-mask" @click.self="closeEditor">
      <div class="th-modal">
        <div class="th-modal-head">
          <div>
            <div class="th-m-num">
              {{ isCreating ? '新增一件小事' : `№ ${pad3(editing.spec.number)}　·　${editing.spec.category}` }}
            </div>
            <h3 class="serif">{{ isCreating ? '想做的事，现在写下来' : editing.spec.title }}</h3>
          </div>
          <button class="th-close" @click="closeEditor">✕</button>
        </div>
        <div class="th-modal-body">
          <div v-if="modalError" class="th-modal-error">{{ modalError }}</div>

          <div class="th-field">
            <label>编号</label>
            <input
              v-model.number="editing.spec.number"
              class="th-input"
              type="number"
              min="1"
              max="999"
            />
          </div>
          <div class="th-field">
            <label>这件小事</label>
            <input
              v-model="editing.spec.title"
              class="th-input"
              type="text"
              maxlength="100"
              placeholder="如：和十七岁的自己聊一次天"
            />
          </div>
          <div class="th-field">
            <label>分类</label>
            <select v-model="editing.spec.category" class="th-input">
              <option value="" disabled>请选择分类</option>
              <option v-for="cat in categoryOptions" :key="cat" :value="cat">{{ cat }}</option>
            </select>
          </div>

          <div class="th-field">
            <label>状态</label>
            <div class="th-status-row">
              <button
                v-for="(text, key) in STATUS_TEXT"
                :key="key"
                :class="{ on: editing.spec.status === key }"
                @click="editing.spec.status = key as SmallThingStatus"
              >
                {{ text }}
              </button>
            </div>
          </div>
          <div class="th-field">
            <label>完成日期</label>
            <input v-model="editing.spec.doneAt" class="th-input" type="date" />
          </div>
          <div class="th-field">
            <label>当时的心情</label>
            <div class="th-mood-grid">
              <button
                v-for="(text, key) in MOOD_TEXT"
                :key="key"
                :class="{ on: editing.spec.mood === key }"
                @click="editing.spec.mood = key as SmallThingMood"
              >
                {{ text }}
              </button>
            </div>
          </div>
          <div class="th-field">
            <label>一句话记录</label>
            <textarea
              v-model="editing.spec.note"
              class="th-textarea"
              maxlength="100"
              placeholder="那天发生了什么…"
            />
          </div>
        </div>
        <div class="th-modal-foot">
          <button
            v-if="!isCreating"
            class="th-btn danger"
            :class="{ armed: confirmDelete }"
            :disabled="deleting"
            @click="removeThing"
          >
            {{ deleting ? '删除中…' : confirmDelete ? '确认删除？' : '删除' }}
          </button>
          <button class="th-btn" @click="closeEditor">取消</button>
          <button class="th-btn primary" :disabled="saving" @click="saveEditor">
            {{ saving ? '保存中…' : isCreating ? '新增' : '保存记录' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.th-workbench {
  --paper: #faf4e8;
  --card: #fffdf7;
  --ink: #3d362c;
  --ink-soft: #7b6e5c;
  --ink-faint: #a5967e;
  --line: #e4d9c4;
  --line-soft: #efe7d6;
  --accent: #b3895f;
  --accent-deep: #8e6a44;
  --accent-wash: #f4e7d2;
  --radius: 14px;

  max-width: 1000px;
  margin: 0 auto;
  padding: 0 8px 60px;
  color: var(--ink);
  font-size: 14px;
  line-height: 1.7;
}
.th-workbench .serif {
  font-family: Georgia, 'Times New Roman', 'Songti SC', 'STSong', 'SimSun', serif;
}

/* ---------- 顶部 ---------- */
.th-header {
  position: sticky;
  top: 0;
  z-index: 20;
  background: rgba(250, 244, 232, 0.94);
  backdrop-filter: blur(10px);
  border-bottom: 1px solid var(--line);
  padding: 12px 0 10px;
  margin-bottom: 8px;
}
.th-brand {
  font-size: 13.5px;
  color: var(--ink-soft);
  letter-spacing: 0.4px;
}
.th-prog {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  margin: 8px 0;
}
.th-prog-text {
  font-size: 12px;
  color: var(--ink-soft);
  letter-spacing: 0.3px;
  white-space: nowrap;
}
.th-prog-text b {
  color: var(--accent-deep);
  font-size: 15px;
  font-family: Georgia, serif;
}
.th-track {
  flex: 1 1 190px;
  min-width: 120px;
  height: 7px;
  border-radius: 99px;
  background: var(--line-soft);
  border: 1px solid var(--line);
  overflow: hidden;
}
.th-fill {
  height: 100%;
  background: linear-gradient(90deg, #d3b183, var(--accent));
  transition: width 0.5s ease;
}
.th-lv {
  font-size: 11.5px;
  color: var(--accent-deep);
  background: var(--accent-wash);
  border: 1px solid #e6d3b3;
  border-radius: 999px;
  padding: 2px 10px;
  white-space: nowrap;
}
.th-lock {
  display: inline-block;
  margin-top: 14px;
  padding: 3px 10px;
  border-radius: 99px;
  background: var(--accent-wash);
  border: 1px solid var(--line);
  color: var(--accent-deep);
  font-size: 11.5px;
  letter-spacing: 0.3px;
}

/* ---------- 页头 ---------- */
.th-head {
  padding: 34px 0 20px;
  text-align: center;
}
.th-head h1 {
  font-size: clamp(21px, 4.2vw, 32px);
  font-weight: 600;
  letter-spacing: 1.5px;
  line-height: 1.4;
}
.th-sub {
  margin-top: 8px;
  color: var(--ink-faint);
  font-size: 12.5px;
  letter-spacing: 3px;
}
.th-quote {
  margin-top: 16px;
  color: var(--ink-soft);
  font-size: 13px;
  letter-spacing: 1.2px;
}
.th-quote::before,
.th-quote::after {
  content: '';
  display: inline-block;
  width: 24px;
  height: 1px;
  background: var(--line);
  vertical-align: middle;
  margin: 0 12px;
}

/* ---------- 工具条 ---------- */
.th-tools {
  display: flex;
  gap: 10px;
  align-items: center;
  margin: 6px 0 20px;
  flex-wrap: wrap;
}
.th-search {
  flex: 1 1 220px;
  min-width: 160px;
  background: var(--card);
  border: 1px solid var(--line);
  border-radius: 999px;
  padding: 9px 15px;
  font-size: 13.5px;
  outline: none;
}
.th-search:focus {
  border-color: var(--accent);
}
.th-filter {
  background: var(--card);
  border: 1px solid var(--line);
  border-radius: 999px;
  padding: 9px 15px;
  font-size: 13px;
  outline: none;
  cursor: pointer;
  color: var(--ink-soft);
}
.th-count {
  margin-left: auto;
  font-size: 12px;
  color: var(--ink-faint);
}

/* ---------- 分组与卡片 ---------- */
.th-group {
  margin-bottom: 30px;
}
.th-group-head {
  display: flex;
  align-items: center;
  gap: 13px;
  margin-bottom: 13px;
}
.th-group-head h2 {
  font-size: 16.5px;
  font-weight: 600;
  letter-spacing: 1.6px;
  white-space: nowrap;
}
.th-rule {
  flex: 1;
  height: 1px;
  background: var(--line);
}
.th-gnum {
  font-size: 11.5px;
  color: var(--ink-faint);
  letter-spacing: 1px;
  white-space: nowrap;
}
.th-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(212px, 1fr));
  gap: 13px;
}
.th-card {
  position: relative;
  background: var(--card);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: 15px 15px 13px;
  cursor: pointer;
  transition: transform 0.18s ease, box-shadow 0.18s ease, border-color 0.18s ease;
  min-height: 128px;
  display: flex;
  flex-direction: column;
}
.th-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 1px 2px rgba(88, 68, 40, 0.05), 0 8px 22px rgba(88, 68, 40, 0.06);
  border-color: #d8c8ab;
}
.th-card-no {
  font-size: 11px;
  color: var(--ink-faint);
  letter-spacing: 1.4px;
  font-family: Georgia, serif;
}
.th-photo {
  position: absolute;
  top: 13px;
  right: 14px;
  font-size: 12px;
  opacity: 0.75;
}
.th-card-name {
  font-size: 14.5px;
  line-height: 1.55;
  margin: 6px 0 10px;
  font-weight: 500;
}
.th-card-foot {
  margin-top: auto;
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.th-pill {
  font-size: 11.5px;
  border-radius: 999px;
  padding: 2px 10px;
  border: 1px solid;
  letter-spacing: 0.4px;
  white-space: nowrap;
}
.th-pill.todo {
  background: #f3ede1;
  color: #8d7f68;
  border-color: #e2d7c1;
}
.th-pill.waiting {
  background: #e6eff1;
  color: #5d8892;
  border-color: #cfe1e4;
}
.th-pill.treasured {
  background: #f7ead0;
  color: #a37b3c;
  border-color: #ecd8ac;
}
.th-pill.later {
  background: #ece7f2;
  color: #7b6d9a;
  border-color: #dbd2e8;
}
.th-card-date {
  font-size: 11.5px;
  color: var(--ink-faint);
  letter-spacing: 0.5px;
}

/* ---------- 章节页 ---------- */
.th-chapter-hero {
  background: var(--card);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: 30px 24px;
  text-align: center;
}
.th-lv-big {
  font-size: clamp(22px, 3.8vw, 30px);
  color: var(--accent);
  letter-spacing: 3px;
  font-family: Georgia, serif;
}
.th-chapter-hero h2 {
  font-size: clamp(18px, 3.2vw, 24px);
  font-weight: 600;
  letter-spacing: 2px;
  margin-top: 6px;
}
.th-say {
  color: var(--ink-soft);
  font-size: 13.5px;
  margin-top: 10px;
  letter-spacing: 0.8px;
}
.th-cnt {
  margin-top: 12px;
  font-size: 13px;
  color: var(--ink-soft);
}
.th-cnt b {
  color: var(--accent-deep);
  font-size: 17px;
  font-family: Georgia, serif;
}
.th-roadmap {
  margin: 26px 0 6px;
}
.th-road-track {
  height: 8px;
  background: var(--line-soft);
  border: 1px solid var(--line);
  border-radius: 99px;
  overflow: hidden;
}
.th-road-fill {
  height: 100%;
  background: linear-gradient(90deg, #dcbf95, var(--accent));
  transition: width 0.6s ease;
}
.th-road-nodes {
  display: flex;
  justify-content: space-between;
  margin-top: 11px;
  flex-wrap: wrap;
  gap: 6px;
}
.th-road-node {
  font-size: 11px;
  color: var(--ink-faint);
  letter-spacing: 0.6px;
  text-align: center;
  flex: 1 1 78px;
  min-width: 64px;
}
.th-rn-dot {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: var(--card);
  border: 1px solid var(--line);
  margin: 0 auto 6px;
}
.th-road-node.on {
  color: var(--accent-deep);
}
.th-road-node.on .th-rn-dot {
  background: var(--accent);
  border-color: var(--accent);
  box-shadow: 0 0 0 3px rgba(179, 137, 95, 0.16);
}
.th-sec-title {
  font-size: 15.5px;
  font-weight: 600;
  letter-spacing: 2px;
  margin: 32px 0 13px;
  display: flex;
  align-items: center;
  gap: 12px;
}
.th-sec-title::after {
  content: '';
  flex: 1;
  height: 1px;
  background: var(--line);
}
.th-stats {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(210px, 1fr));
  gap: 13px;
}
.th-stat-card {
  background: var(--card);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: 17px 18px;
}
.th-stat-t {
  font-size: 12px;
  color: var(--ink-faint);
  letter-spacing: 1.4px;
}
.th-stat-v {
  font-size: 27px;
  font-family: Georgia, serif;
  color: var(--accent-deep);
  margin-top: 6px;
}
.th-stat-v small {
  font-size: 13px;
  color: var(--ink-soft);
  letter-spacing: 0.5px;
}
.th-muted {
  color: var(--ink-faint);
  font-size: 12.5px;
  margin-top: 8px;
}
.th-mood-row {
  display: flex;
  align-items: center;
  gap: 9px;
  font-size: 13px;
  margin-top: 9px;
}
.th-mn {
  width: 42px;
  color: var(--ink-soft);
  letter-spacing: 0.6px;
}
.th-mb {
  flex: 1;
  height: 7px;
  background: var(--line-soft);
  border-radius: 99px;
  overflow: hidden;
}
.th-mb i {
  display: block;
  height: 100%;
  background: var(--accent-wash);
  border-right: 1px solid #ddc49c;
}
.th-mc {
  width: 26px;
  text-align: right;
  color: var(--ink-faint);
  font-family: Georgia, serif;
}
.th-cloud {
  display: flex;
  flex-wrap: wrap;
  gap: 7px 10px;
  align-items: baseline;
  margin-top: 9px;
}
.th-cloud span {
  color: var(--accent-deep);
  letter-spacing: 1px;
  line-height: 1.35;
}
.th-cloud .c-lg {
  font-size: 19px;
}
.th-cloud .c-md {
  font-size: 16px;
}
.th-cloud .c-sm {
  font-size: 13px;
  color: var(--ink-soft);
}
.th-letter {
  background: var(--card);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: 24px 26px;
  position: relative;
}
.th-letter::before {
  content: '';
  position: absolute;
  left: 50%;
  top: -9px;
  width: 92px;
  height: 19px;
  transform: translateX(-50%) rotate(-1.4deg);
  background: rgba(214, 186, 140, 0.42);
}
.th-letter p {
  font-size: 15px;
  line-height: 2.1;
  letter-spacing: 0.6px;
}
.th-sign {
  text-align: right;
  color: var(--ink-faint);
  font-size: 12.5px;
  margin-top: 13px;
  letter-spacing: 1px;
}
.th-timeline {
  position: relative;
  padding-left: 24px;
}
.th-timeline::before {
  content: '';
  position: absolute;
  left: 6px;
  top: 6px;
  bottom: 6px;
  width: 1px;
  background: var(--line);
}
.th-tl-item {
  position: relative;
  padding-bottom: 18px;
}
.th-tl-item::before {
  content: '';
  position: absolute;
  left: -21px;
  top: 7px;
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: var(--card);
  border: 1px solid var(--accent);
}
.th-tl-date {
  font-size: 11.5px;
  color: var(--ink-faint);
  letter-spacing: 1.2px;
  font-family: Georgia, serif;
}
.th-tl-name {
  font-size: 14px;
  margin-top: 3px;
  font-weight: 500;
}
.th-tl-note {
  font-size: 12.5px;
  color: var(--ink-soft);
  margin-top: 3px;
  line-height: 1.75;
}
.th-tl-mood {
  display: inline-block;
  margin-top: 5px;
  font-size: 11px;
  color: var(--accent-deep);
  background: var(--accent-wash);
  border: 1px solid #e8d5b5;
  border-radius: 999px;
  padding: 1px 9px;
}

/* ---------- 状态与错误 ---------- */
.th-state {
  padding: 48px 0;
  text-align: center;
  color: var(--ink-faint);
  font-size: 13.5px;
  letter-spacing: 0.5px;
}
.th-error {
  margin: 14px 0;
  padding: 12px 16px;
  border: 1px solid #e0c3b0;
  background: #f8ece2;
  border-radius: 10px;
  color: #8d5a3c;
  font-size: 13px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.th-error button {
  border: 1px solid #d3ae95;
  background: #fffdf8;
  border-radius: 6px;
  padding: 3px 11px;
  cursor: pointer;
  font-size: 12.5px;
  white-space: nowrap;
}

/* ---------- 记录面板 ---------- */
.th-mask {
  position: fixed;
  inset: 0;
  z-index: 100;
  background: rgba(60, 46, 28, 0.42);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  backdrop-filter: blur(3px);
}
.th-modal {
  background: var(--card);
  border: 1px solid var(--line);
  border-radius: 16px;
  width: 100%;
  max-width: 520px;
  max-height: 88vh;
  display: flex;
  flex-direction: column;
  box-shadow: 0 18px 50px rgba(70, 52, 30, 0.22);
}
.th-modal-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 19px 21px 13px;
  border-bottom: 1px solid var(--line-soft);
}
.th-m-num {
  font-size: 11.5px;
  color: var(--ink-faint);
  letter-spacing: 1.5px;
  font-family: Georgia, serif;
}
.th-modal-head h3 {
  font-size: 17px;
  font-weight: 600;
  letter-spacing: 0.8px;
  margin-top: 3px;
  line-height: 1.5;
}
.th-close {
  border: none;
  background: var(--paper);
  width: 29px;
  height: 29px;
  border-radius: 50%;
  cursor: pointer;
  color: var(--ink-soft);
  font-size: 14px;
  flex: 0 0 auto;
}
.th-modal-body {
  padding: 17px 21px;
  overflow-y: auto;
}
.th-field {
  margin-bottom: 16px;
}
.th-field label {
  display: block;
  font-size: 12px;
  color: var(--ink-faint);
  letter-spacing: 1.4px;
  margin-bottom: 8px;
}
.th-status-row,
.th-mood-grid {
  display: flex;
  gap: 7px;
  flex-wrap: wrap;
}
.th-status-row button,
.th-mood-grid button {
  border: 1px solid var(--line);
  background: var(--paper);
  border-radius: 999px;
  padding: 5px 13px;
  cursor: pointer;
  font-size: 12.5px;
  color: var(--ink-soft);
}
.th-status-row button.on {
  background: var(--ink);
  color: var(--card);
  border-color: var(--ink);
}
.th-mood-grid button.on {
  background: var(--accent-wash);
  border-color: #dfc49c;
  color: var(--accent-deep);
}
.th-input,
.th-textarea {
  width: 100%;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--paper);
  padding: 9px 12px;
  outline: none;
  font-size: 13.5px;
}
.th-textarea {
  min-height: 78px;
  resize: vertical;
  line-height: 1.8;
}
.th-input:focus,
.th-textarea:focus {
  border-color: var(--accent);
}
.th-modal-foot {
  display: flex;
  gap: 9px;
  justify-content: flex-end;
  padding: 13px 21px 17px;
  border-top: 1px solid var(--line-soft);
}
.th-btn {
  border: 1px solid var(--line);
  background: var(--paper);
  border-radius: 9px;
  padding: 9px 20px;
  cursor: pointer;
  font-size: 13.5px;
  color: var(--ink-soft);
  letter-spacing: 0.6px;
}
.th-btn.primary {
  background: var(--ink);
  border-color: var(--ink);
  color: var(--card);
}
.th-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* ---------- 提示条 ---------- */
.th-notice {
  margin: -8px 0 16px;
  padding: 9px 14px;
  border-radius: 10px;
  background: var(--accent-wash);
  border: 1px solid #e6d3b3;
  color: var(--accent-deep);
  font-size: 12.5px;
}

/* ---------- 删除按钮（两段式确认） ---------- */
.th-btn.danger {
  margin-right: auto;
  color: #9c4a4a;
  border-color: #e3b9b9;
  background: #fff7f7;
}
.th-btn.danger.armed {
  background: #b4544f;
  border-color: #9c4a4a;
  color: #fff;
}

.th-modal-error {
  margin-bottom: 12px;
  padding: 9px 12px;
  border-radius: 9px;
  background: #fdeeee;
  border: 1px solid #f3d2d2;
  color: #9c4a4a;
  font-size: 12.5px;
  line-height: 1.6;
}

/* ---------- 章节设置 ---------- */
.th-ch-edit {
  background: var(--card);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: 16px 18px 18px;
}
.th-ch-hint {
  margin: 0 0 12px;
  font-size: 12.5px;
  color: var(--ink-faint);
  line-height: 1.7;
}
.th-ch-row {
  display: flex;
  align-items: center;
  gap: 9px;
  flex-wrap: wrap;
  padding: 9px 0;
  border-top: 1px solid var(--line-soft);
}
.th-ch-lv {
  flex: 0 0 auto;
  font-size: 11.5px;
  color: var(--accent-deep);
  background: var(--accent-wash);
  border: 1px solid #e6d3b3;
  border-radius: 999px;
  padding: 1px 9px;
}
.th-ch-name {
  flex: 0 0 150px;
  width: auto;
}
.th-ch-say {
  flex: 1 1 220px;
  width: auto;
}
.th-ch-min {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--ink-faint);
  white-space: nowrap;
}
.th-ch-min .th-input {
  width: 84px;
  padding: 7px 9px;
}
.th-ch-del {
  border: none;
  background: none;
  color: #9c4a4a;
  font-size: 12.5px;
  font-family: inherit;
  cursor: pointer;
  padding: 4px 6px;
}
.th-ch-del:hover {
  text-decoration: underline;
}
.th-ch-foot {
  display: flex;
  align-items: center;
  gap: 9px;
  flex-wrap: wrap;
  padding-top: 13px;
  border-top: 1px dashed var(--line);
}
.th-ch-notice {
  margin-right: auto;
  font-size: 12.5px;
  color: var(--accent-deep);
}

@media (max-width: 640px) {
  .th-grid {
    grid-template-columns: 1fr;
  }
  .th-stats {
    grid-template-columns: 1fr;
  }
  .th-letter {
    padding: 20px 18px;
  }
}
</style>
