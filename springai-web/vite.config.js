import {defineConfig} from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
    plugins: [vue()],
    server: {
        host: 'localhost',
        port: 8580,
        // 开发期把 /api 代理到后端，前端代码里就不用写死 http://localhost:5501，也不会有跨域问题
        proxy: {
            '/api': {
                target: 'http://localhost:5501',
                changeOrigin: true
            }
        }
    }
})