/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** 纯前端演示模式开关：设为 true（且非 false 字符串）时 ApiClient 注入 mock 传输器。 */
  readonly VITE_MOCK?: string
}
