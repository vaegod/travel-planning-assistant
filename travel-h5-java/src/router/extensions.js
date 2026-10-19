// Explicit opt-in for the retained standalone extension; normal navigation never links here.
export function knowledgeExtensionRoutes(enabled = false) {
  return [enabled
    ? {path:'/knowledge',name:'Knowledge',component:()=>import('../views/Knowledge.vue')}
    : {path:'/knowledge',redirect:'/'}];
}
