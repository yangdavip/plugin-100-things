<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  CHAPTERS,
  CATEGORIES,
  MOOD_TEXT,
  STATUS_TEXT,
  fetchStats,
  listSmallThings,
  updateSmallThing,
  type SmallThing,
  type SmallThingMood,
  type SmallThingStatus,
} from '@/api/smallThings'

const things = ref<SmallThing[]>([])
const loading = ref(true)
const errorMessage = ref('')
const keyword = ref('')
const categoryFilter = ref('')
const activeTab = ref<'list' | 'chapter'>('list')
const editing = ref<SmallThing | null>(null)
const saving = ref(false)

/** 已珍藏件数。 */
const treasuredCount = computed(
  () =>
    things.value.filter((t) => t.spec.status === 'TREASURED').length
)

/** 当前章节（按已珍藏件数取阈值不超过的最大章节）。 */
const currentChapter = computed(() => {
  let current = CHAPTERS[0]!
  for (const chapter of CHAPTERS) {
    if (treasuredCount.value >= chapter.min) {
      current = chapter
    }
  }
  return current
})

/** 下一个尚未达成的章节。 */
const nextChapter = computed(() =>
  CHAPTERS.find((c) => treasuredCount.value < c.min)
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
    text = `你已经认真完成了 ${n} 件小事。它们散落在不同的日子里，可能你自己都记不清了。再攒 ${remaining.value} 件，你就走到「${nextChapter.value.name.split(' · ')[1]}」了。日子不急，小事也不急，慢慢来。`
  } else {
    text = '100 件小事，全部被一一收藏。你走完了这张清单，也走完了一段人生。别回头看，看前面就好。'
  }
  const latest = recentTreasured.value[0]
  if (latest && latest.spec.note) {
    text += `　你最近写下的那句话是：「${latest.spec.note}」。`
  }
  return text
})

/** 加载数据。 */
async function load() {
  loading.value = true
  errorMessage.value = ''
  try {
    things.value = await listSmallThings()
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

/** 打开记录面板。 */
function openEditor(thing: SmallThing) {
  editing.value = thing
}

/** 关闭记录面板。 */
function closeEditor() {
  editing.value = null
}

/** 保存记录（增量更新：只提交变更字段）。 */
async function saveEditor() {
  const target = editing.value
  if (!target || saving.value) {
    return
  }
  saving.value = true
  try {
    const spec: Partial<SmallThing['spec']> = {}
    if (target.spec.status !== editing.value!.spec.status) {
      spec.status = target.spec.status
    }
    if ((target.spec.doneAt || '') !== (editing.value!.spec.doneAt || '')) {
      spec.doneAt = target.spec.doneAt
    }
    if ((target.spec.mood || '') !== (editing.value!.spec.mood || '')) {
      spec.mood = target.spec.mood
    }
    if ((target.spec.note || '') !== (editing.value!.spec.note || '')) {
      spec.note = target.spec.note
    }
    if (Object.keys(spec).length) {
      await updateSmallThing(target.metadata.name, spec)
    }
    await load()
    closeEditor()
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : String(e)
  } finally {
    saving.value = false
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
      <nav class="th-tabs">
        <button :class="{ on: activeTab === 'list' }" @click="activeTab = 'list'">
          100 件小事
        </button>
        <button :class="{ on: activeTab === 'chapter' }" @click="activeTab = 'chapter'">
          我的生活章节
        </button>
      </nav>
    </header>

    <div v-if="errorMessage" class="th-error">
      <span>{{ errorMessage }}</span>
      <button @click="load">重试</button>
    </div>

    <div v-if="loading" class="th-state">正在读取小事清单…</div>

    <!-- ============ 卡片墙 ============ -->
    <section v-else-if="activeTab === 'list'">
      <div class="th-head">
        <h1 class="serif">短暂人生，你想做的 100 件事情</h1>
        <div class="th-sub">一百件小事 · 一生的清单</div>
        <div class="th-quote">有些事情不是为了完成，而是为了记住。</div>
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
      </div>

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
            v-for="chapter in CHAPTERS"
            :key="chapter.lv"
            class="th-road-node"
            :class="{ on: treasuredCount >= chapter.min }"
          >
            <div class="th-rn-dot" />
            <div class="th-rn-name">{{ chapter.name.split(' · ')[1] }}</div>
            <div class="th-rn-min">{{ chapter.min }} 件</div>
          </div>
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
              № {{ pad3(editing.spec.number) }}　·　{{ editing.spec.category }}
            </div>
            <h3 class="serif">{{ editing.spec.title }}</h3>
          </div>
          <button class="th-close" @click="closeEditor">✕</button>
        </div>
        <div class="th-modal-body">
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
          <button class="th-btn" @click="closeEditor">取消</button>
          <button class="th-btn primary" :disabled="saving" @click="saveEditor">
            {{ saving ? '保存中…' : '保存记录' }}
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
.th-tabs {
  display: flex;
  gap: 4px;
  background: var(--card);
  border: 1px solid var(--line);
  border-radius: 999px;
  padding: 3px;
  width: fit-content;
}
.th-tabs button {
  border: none;
  background: transparent;
  color: var(--ink-soft);
  padding: 6px 17px;
  border-radius: 999px;
  cursor: pointer;
  font-size: 13px;
  letter-spacing: 0.3px;
  transition: all 0.18s ease;
  white-space: nowrap;
}
.th-tabs button.on {
  background: var(--ink);
  color: #fffdf7;
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
