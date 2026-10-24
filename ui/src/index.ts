import { definePlugin } from '@halo-dev/ui-shared'
import { markRaw } from 'vue'
import IconCheckboxMultiple from '~icons/ri/checkbox-multiple-line'

export default definePlugin({
  components: {},
  routes: [
    {
      parentName: 'Root',
      route: {
        path: '/plugins/plugin-100-things',
        name: 'Plugin100Things',
        component: () =>
          import(
            /* webpackChunkName: "SmallThingsView" */ './views/SmallThingsView.vue'
          ),
        meta: {
          title: '100 件小事',
          searchable: true,
          permissions: ['plugin:plugin-100-things:smallthing:view'],
          menu: {
            name: '100 件小事',
            group: '内容',
            icon: markRaw(IconCheckboxMultiple),
            priority: 0,
          },
        },
      },
    },
  ],
  extensionPoints: {},
})
