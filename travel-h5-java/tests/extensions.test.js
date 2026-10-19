import test from 'node:test';
import assert from 'node:assert/strict';
import {createRouter,createMemoryHistory} from 'vue-router';
import {knowledgeExtensionRoutes} from '../src/router/extensions.js';

test('old knowledge bookmarks resolve to home by default without loading the extension',async()=>{
  const routes=knowledgeExtensionRoutes();assert.equal(routes[0].component,undefined);
  const router=createRouter({history:createMemoryHistory(),routes:[{path:'/',component:{}},...routes]});
  await router.push('/knowledge');assert.equal(router.currentRoute.value.path,'/');
});

test('the retained standalone page requires explicit opt-in',()=>{
  const [route]=knowledgeExtensionRoutes(true);
  assert.equal(route.name,'Knowledge');assert.equal(typeof route.component,'function');assert.equal(route.redirect,undefined);
});
