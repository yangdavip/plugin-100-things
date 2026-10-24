<script setup lang="ts">
/**
 * 「100 件小事」的展示配置页。
 *
 * 这里配置**页面本身**：标题、副标题、引言、页脚、进度条、是否分组。
 * 清单条目请到顶部「小事清单」页签（可增删改），生活章节请到「生活章节」页签。
 *
 * 配置存在插件自己的 ConfigMap（`plugin-100-things-config`）里，保存后前台
 * 下一次请求即生效，不需要重启插件、也不需要重新发版。
 */
import { computed, onMounted, ref } from 'vue'
import {
  fetchSettings,
  saveSettings,
  type SmallThingSettings,
} from '@/api/smallThings'

const config = ref<SmallThingSettings | null>(null)
const defaults = ref<SmallThingSettings | null>(null)
const pagePath = ref('')
const loading = ref(true)
const saving = ref(false)
const errorMessage = ref('')
const notice = ref('')

/** 前台完整地址（用于显示与复制）。 */
const pageUrl = computed(() => {
  if (!pagePath.value) {
    return ''
  }
  if (typeof window === 'undefined') {
    return pagePath.value
  }
  return `${window.location.origin}${pagePath.value}`
})

/** 读取配置。 */
async function load() {
  loading.value = true
  errorMessage.value = ''
  try {
    const response = await fetchSettings()
    config.value = structuredClone(response.config)
    defaults.value = structuredClone(response.defaults)
    pagePath.value = response.pagePath
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

/** 保存配置。 */
async function submit() {
  if (!config.value || saving.value) {
    return
  }
  saving.value = true
  errorMessage.value = ''
  notice.value = ''
  try {
    const response = await saveSettings(config.value)
    // 以服务端规范化后的结果为准（它可能截断了超长文本、把空标题回退成默认值）
    config.value = structuredClone(response.config)
    defaults.value = structuredClone(response.defaults)
    notice.value = '已保存，前台刷新即可看到效果'
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : String(e)
  } finally {
    saving.value = false
  }
}

/** 恢复成默认配置（只填回表单，仍需点保存才落库）。 */
function resetToDefaults() {
  if (!defaults.value) {
    return
  }
  // 深拷贝：chapters 是数组，直接展开会让表单和 defaults 共用同一个引用
  config.value = structuredClone(defaults.value)
  notice.value = '已填回默认值，点「保存配置」后生效'
}

/** 复制前台地址。 */
async function copyPath() {
  if (!pageUrl.value) {
    return
  }
  try {
    await navigator.clipboard.writeText(pageUrl.value)
    notice.value = '已复制前台地址'
  } catch {
    // 非安全上下文（http 且非 localhost）下剪贴板不可用，此时让用户手动选中
    notice.value = '浏览器不允许自动复制，请手动选中地址复制'
  }
}

onMounted(load)
</script>

<template>
  <div class="th-conf">
    <div v-if="errorMessage" class="th-conf-alert">
      <span>{{ errorMessage }}</span>
      <button type="button" @click="load">重试</button>
    </div>

    <div v-if="loading" class="th-conf-state">正在读取配置…</div>

    <template v-else-if="config">
      <!-- 前台入口 -->
      <section class="th-conf-card">
        <div class="th-conf-head">
          <h2>博客前台地址</h2>
          <p>插件启用后，这个地址就是访客看到的清单页。访客只读，改动都在控制台完成。</p>
        </div>
        <div class="th-conf-url">
          <code>{{ pageUrl }}</code>
          <button type="button" class="th-btn" @click="copyPath">复制</button>
          <a class="th-btn primary" :href="pagePath" target="_blank" rel="noopener">
            打开前台
          </a>
        </div>
      </section>

      <!-- 管理入口提示：配置页只管文案，清单与章节在别的页签，不写清楚会以为「没地方改」 -->
      <div class="th-conf-tip">
        清单条目（编号 / 名字 / 分类 / 删除）在顶部
        <b>小事清单</b> 页签里改；生活章节（章节名、寄语、多少件解锁）在
        <b>生活章节</b> 页签里改。
      </div>

      <!-- 文案 -->
      <section class="th-conf-card">
        <div class="th-conf-head">
          <h2>页面文案</h2>
          <p>留空的标题与副标题会回退成默认文案；页脚留空则不显示。</p>
        </div>

        <div class="th-conf-field">
          <label for="th-page-title">页面标题</label>
          <input id="th-page-title" v-model="config.pageTitle" type="text" maxlength="120" />
        </div>

        <div class="th-conf-field">
          <label for="th-page-subtitle">副标题</label>
          <input
            id="th-page-subtitle"
            v-model="config.pageSubtitle"
            type="text"
            maxlength="120"
          />
        </div>

        <div class="th-conf-field">
          <label for="th-page-quote">引言</label>
          <input id="th-page-quote" v-model="config.pageQuote" type="text" maxlength="120" />
        </div>

        <div class="th-conf-field">
          <label for="th-footer-note">页脚签名</label>
          <input
            id="th-footer-note"
            v-model="config.footerNote"
            type="text"
            maxlength="120"
            placeholder="留空则不显示"
          />
        </div>
      </section>

      <!-- 展示方式 -->
      <section class="th-conf-card">
        <div class="th-conf-head">
          <h2>展示方式</h2>
          <p>只影响前台的呈现，不影响已记录的打卡数据。</p>
        </div>

        <div class="th-conf-switch">
          <div>
            <div class="th-conf-switch-title">显示进度条</div>
            <div class="th-conf-switch-desc">
              在页面顶部显示「已珍藏 N / 100 件」与进度条
            </div>
          </div>
          <button
            type="button"
            role="switch"
            class="th-sw"
            :class="{ on: config.showProgress }"
            :aria-checked="config.showProgress"
            aria-label="显示进度条"
            @click="config.showProgress = !config.showProgress"
          >
            <span class="th-sw-dot" />
          </button>
        </div>

        <div class="th-conf-switch">
          <div>
            <div class="th-conf-switch-title">按分类分组</div>
            <div class="th-conf-switch-desc">
              关闭后所有小事平铺成一张网格，不再显示分类标题
            </div>
          </div>
          <button
            type="button"
            role="switch"
            class="th-sw"
            :class="{ on: config.groupByCategory }"
            :aria-checked="config.groupByCategory"
            aria-label="按分类分组"
            @click="config.groupByCategory = !config.groupByCategory"
          >
            <span class="th-sw-dot" />
          </button>
        </div>
      </section>

      <div class="th-conf-foot">
        <span v-if="notice" class="th-conf-notice">{{ notice }}</span>
        <button type="button" class="th-btn" @click="resetToDefaults">恢复默认</button>
        <button type="button" class="th-btn primary" :disabled="saving" @click="submit">
          {{ saving ? '保存中…' : '保存配置' }}
        </button>
      </div>
    </template>
  </div>
</template>

<style scoped>
.th-conf {
  --paper: #faf4e8;
  --card: #fffdf7;
  --ink: #3d362c;
  --ink-soft: #7b6e5c;
  --ink-faint: #a5967e;
  --line: #e4d9c4;
  --line-soft: #efe7d6;
  --accent: #b3895f;
  --accent-deep: #8e6a44;

  max-width: 780px;
  margin: 0 auto;
  padding: 4px 4px 48px;
  color: var(--ink);
  font-size: 14px;
  line-height: 1.7;
}

.th-conf-alert {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
  padding: 10px 14px;
  border-radius: 10px;
  background: #fdeeee;
  border: 1px solid #f3d2d2;
  color: #9c4a4a;
  font-size: 13px;
}
.th-conf-alert button {
  flex: 0 0 auto;
  padding: 4px 10px;
  border-radius: 8px;
  border: 1px solid #e3b9b9;
  background: #fff;
  color: #9c4a4a;
  cursor: pointer;
}

.th-conf-state {
  padding: 60px 0;
  text-align: center;
  color: var(--ink-faint);
  font-size: 13.5px;
}

.th-conf-card {
  background: var(--card);
  border: 1px solid var(--line);
  border-radius: 14px;
  padding: 20px 22px 22px;
  margin-bottom: 16px;
  box-shadow: 0 1px 0 rgba(61, 54, 44, 0.03);
}

.th-conf-tip {
  margin: -4px 0 16px;
  padding: 11px 14px;
  border-radius: 10px;
  background: var(--paper);
  border: 1px dashed var(--line);
  color: var(--ink-soft);
  font-size: 12.5px;
  line-height: 1.75;
}
.th-conf-tip b {
  color: var(--accent-deep);
  font-weight: 600;
}

.th-conf-head h2 {
  margin: 0 0 4px;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 0.3px;
}
.th-conf-head p {
  margin: 0 0 16px;
  font-size: 12.5px;
  color: var(--ink-faint);
  line-height: 1.6;
}

.th-conf-url {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.th-conf-url code {
  flex: 1 1 300px;
  min-width: 0;
  padding: 9px 12px;
  border-radius: 9px;
  border: 1px dashed var(--line);
  background: var(--paper);
  color: var(--ink-soft);
  font-size: 12.5px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.th-conf-field {
  margin-bottom: 14px;
}
.th-conf-field label {
  display: block;
  margin-bottom: 6px;
  font-size: 12.5px;
  color: var(--ink-soft);
  letter-spacing: 0.3px;
}
.th-conf-field input {
  width: 100%;
  padding: 9px 12px;
  border-radius: 9px;
  border: 1px solid var(--line);
  background: var(--paper);
  color: var(--ink);
  font-size: 13.5px;
  font-family: inherit;
  outline: none;
  transition: border-color 0.18s ease, background 0.18s ease;
}
.th-conf-field input:focus {
  border-color: var(--accent);
  background: #fff;
}

.th-conf-switch {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 0;
  border-top: 1px solid var(--line-soft);
}
.th-conf-switch:first-of-type {
  border-top: none;
  padding-top: 4px;
}
.th-conf-switch-title {
  font-size: 13.5px;
  color: var(--ink);
}
.th-conf-switch-desc {
  font-size: 12.5px;
  color: var(--ink-faint);
  line-height: 1.6;
}

.th-sw {
  flex: 0 0 auto;
  position: relative;
  width: 44px;
  height: 24px;
  border-radius: 99px;
  border: 1px solid var(--line);
  background: var(--line-soft);
  cursor: pointer;
  transition: background 0.2s ease, border-color 0.2s ease;
  padding: 0;
}
.th-sw.on {
  background: var(--accent);
  border-color: var(--accent-deep);
}
.th-sw-dot {
  position: absolute;
  top: 2px;
  left: 2px;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: #fff;
  box-shadow: 0 1px 2px rgba(61, 54, 44, 0.25);
  transition: transform 0.2s ease;
}
.th-sw.on .th-sw-dot {
  transform: translateX(20px);
}

.th-conf-foot {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  flex-wrap: wrap;
  padding-top: 4px;
}
.th-conf-notice {
  margin-right: auto;
  font-size: 12.5px;
  color: var(--accent-deep);
}

.th-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 16px;
  border-radius: 9px;
  border: 1px solid var(--line);
  background: var(--card);
  color: var(--ink-soft);
  font-size: 13px;
  font-family: inherit;
  text-decoration: none;
  cursor: pointer;
  transition: background 0.18s ease, border-color 0.18s ease;
}
.th-btn:hover {
  background: var(--paper);
  border-color: var(--accent);
}
.th-btn.primary {
  background: var(--accent);
  border-color: var(--accent-deep);
  color: #fff;
}
.th-btn.primary:hover {
  background: var(--accent-deep);
}
.th-btn[disabled] {
  opacity: 0.6;
  cursor: default;
}
</style>
