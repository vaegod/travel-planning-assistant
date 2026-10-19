import { createRouter, createWebHistory } from 'vue-router'
import { auth, ensureAuth } from '../utils/auth'
import { knowledgeExtensionRoutes } from './extensions'

const routes = [
    ...knowledgeExtensionRoutes(import.meta.env.VITE_KNOWLEDGE_EXTENSION === 'true'),
    {path:'/planning/:id',redirect:'/'},
    { path:'/login', name:'Login', component:()=>import('../views/Login.vue'), meta:{public:true} },
    { path:'/trips', name:'Trips', component:()=>import('../views/Trips.vue') },
    { path:'/conversations', name:'Conversations', component:()=>import('../views/Conversations.vue') },
    { path:'/chat/:id', name:'SavedChat', component:()=>import('../views/Chat.vue') },
    {
        path: '/',
        name: 'Home',
        component: () => import('../views/Home.vue')
    },
    {
        path: '/chat',
        name: 'Chat',
        component: () => import('../views/Chat.vue')
    },
    {
        path: '/profile',
        name: 'Profile',
        component: () => import('../views/Profile.vue')
    },
    {
        path: '/detail',
        name: 'Detail',
        component: () => import('../views/Detail.vue')
    }
]

const router = createRouter({
    history: createWebHistory(),
    routes,
    scrollBehavior(to, from, savedPosition) {
        if (savedPosition) return savedPosition
        if (to.path === from.path) return false
        return { top: 0 }
    }
})

router.beforeEach(async to=>{
    if(to.meta.public)return true
    try{await ensureAuth()}catch{return {path:'/login',query:{redirect:to.fullPath}}}
    if(!auth.user)return {path:'/login',query:{redirect:to.fullPath}}
    return true
})
export default router
