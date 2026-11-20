import {createApp} from 'vue'
import './style.css'
import App from './App.vue'

// Element Plus 全量注册（项目规模小，省去按需引入的构建配置）
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import {zhCn} from 'element-plus/es/locale/index'

const app = createApp(App)
app.use(ElementPlus, {locale: zhCn})
app.mount('#app')