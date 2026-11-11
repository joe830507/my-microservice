/// <reference types="vite/client" />

// 自訂的環境變數；在這裡宣告，import.meta.env.VITE_API_BASE 才有型別
interface ImportMetaEnv {
  readonly VITE_API_BASE: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
