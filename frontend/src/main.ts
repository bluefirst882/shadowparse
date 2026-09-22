import { createApp } from 'vue'
import {
  ElAlert,
  ElButton,
  ElContainer,
  ElHeader,
  ElInput,
  ElMain,
  ElProgress,
  ElTable,
  ElTableColumn,
  ElTag,
  ElUpload,
  vLoading
} from 'element-plus'
import 'element-plus/es/components/alert/style/css'
import 'element-plus/es/components/button/style/css'
import 'element-plus/es/components/container/style/css'
import 'element-plus/es/components/input/style/css'
import 'element-plus/es/components/progress/style/css'
import 'element-plus/es/components/table/style/css'
import 'element-plus/es/components/tag/style/css'
import 'element-plus/es/components/upload/style/css'
import './style.css'
import App from './App.vue'

const app = createApp(App)
app.component('ElAlert', ElAlert)
app.component('ElButton', ElButton)
app.component('ElContainer', ElContainer)
app.component('ElHeader', ElHeader)
app.component('ElInput', ElInput)
app.component('ElMain', ElMain)
app.component('ElProgress', ElProgress)
app.component('ElTable', ElTable)
app.component('ElTableColumn', ElTableColumn)
app.component('ElTag', ElTag)
app.component('ElUpload', ElUpload)
app.directive('loading', vLoading)
app.mount('#app')
