declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<{}, {}, any>
  export default component
}

interface ImportMetaEnv {
  readonly VITE_API_BASE?: string
  readonly VITE_MONITOR_API_BASE?: string
  readonly VITE_MONITOR_CONTROL_TOKEN?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
