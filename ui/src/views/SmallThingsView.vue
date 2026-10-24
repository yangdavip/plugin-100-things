<script setup lang="ts">
/**
 * 「100 件小事」的控制台外壳。
 *
 * 三个页签：配置（默认打开）/ 小事清单 / 生活章节。
 *
 * - 配置：页面文案与展示方式，存 ConfigMap，改完立即生效。
 * - 小事清单 / 生活章节：打卡看板。**只记录进度**（状态、日期、心情、一句话），
 *   清单文案是内置内容，界面与接口都不提供修改入口。
 */
import { ref } from 'vue'
import SmallThingsBoard from './SmallThingsBoard.vue'
import SmallThingsSettings from './SmallThingsSettings.vue'

type TabKey = 'settings' | 'list' | 'chapter'

const tab = ref<TabKey>('settings')

const tabs: Array<{ key: TabKey; label: string }> = [
  { key: 'settings', label: '配置' },
  { key: 'list', label: '小事清单' },
  { key: 'chapter', label: '生活章节' },
]
</script>

<template>
  <div class="th-console">
    <header class="th-console-bar">
      <div class="th-console-title">
        <span class="serif">100 件小事</span>
        <span class="th-console-tag">清单内容只读</span>
      </div>
      <nav class="th-console-tabs">
        <button
          v-for="item in tabs"
          :key="item.key"
          type="button"
          :class="{ on: tab === item.key }"
          @click="tab = item.key"
        >
          {{ item.label }}
        </button>
      </nav>
    </header>

    <SmallThingsSettings v-if="tab === 'settings'" />
    <SmallThingsBoard v-else :view="tab" />
  </div>
</template>

<style scoped>
.th-console {
  --paper: #faf4e8;
  --card: #fffdf7;
  --ink: #3d362c;
  --ink-soft: #7b6e5c;
  --ink-faint: #a5967e;
  --line: #e4d9c4;
  --line-soft: #efe7d6;
  --accent: #b3895f;
  --accent-deep: #8e6a44;

  max-width: 1000px;
  margin: 0 auto;
  padding: 18px 12px 60px;
  color: var(--ink);
  font-size: 14px;
  line-height: 1.7;
}
.th-console .serif {
  font-family: Georgia, 'Times New Roman', 'Songti SC', 'STSong', 'SimSun', serif;
}

.th-console-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  padding: 0 4px 14px;
  margin-bottom: 16px;
  border-bottom: 1px solid var(--line);
}

.th-console-title {
  display: flex;
  align-items: center;
  gap: 10px;
}
.th-console-title .serif {
  font-size: 17px;
  letter-spacing: 0.4px;
}
.th-console-tag {
  padding: 2px 8px;
  border-radius: 99px;
  background: var(--line-soft);
  border: 1px solid var(--line);
  color: var(--ink-faint);
  font-size: 11.5px;
  letter-spacing: 0.3px;
}

.th-console-tabs {
  display: inline-flex;
  gap: 4px;
  padding: 3px;
  border-radius: 11px;
  background: var(--paper);
  border: 1px solid var(--line);
}
.th-console-tabs button {
  padding: 6px 14px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--ink-soft);
  font-size: 13px;
  font-family: inherit;
  cursor: pointer;
  transition: background 0.18s ease, color 0.18s ease;
}
.th-console-tabs button:hover {
  color: var(--ink);
}
.th-console-tabs button.on {
  background: var(--card);
  color: var(--accent-deep);
  box-shadow: 0 1px 2px rgba(61, 54, 44, 0.12);
}
</style>
