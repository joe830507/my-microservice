import { createApp } from 'vue'
import { createPinia } from 'pinia'

import './styles/tokens.css'
import './styles/base.css'
import App from './App.vue'
import router from './router'

const app = createApp(App)

// pinia 要在 router 之前註冊：之後路由守衛會用到 auth store（Step 2）
app.use(createPinia())
app.use(router)

app.mount('#app')
