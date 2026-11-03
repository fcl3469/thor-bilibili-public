/* Fixture checks for the browser adapter; no network or account access. */
'use strict';
const fs = require('fs'), vm = require('vm'), assert = require('assert');
const code = fs.readFileSync(require('path').join(__dirname, '../assets/xunlei-cloud.js'), 'utf8');
let checks = 0;
function fixture(options = {}) {
  const calls = [], handlers = {};
  const api = {
    async findFiles(args) {
      calls.push(['list', args]);
      return options.list || {files: [{id: 'folder', name: 'Folder', kind: 'drive#folder'},
        {id: 'video', name: 'clip.mp4', mime_type: 'video/mp4', size: '123'}], next_page_token: 'next'};
    },
    async getFileInfo(...args) {
      calls.push(['play', args]);
      if (options.failure) throw options.failure;
      return options.media || {id: 'video', name: 'clip.mp4', mime_type: 'video/mp4',
        web_content_link: 'https://media.example/clip.mp4?sign=fixture',
        medias: [{media_name: '1080P', mime_type: 'application/x-mpegURL', link: {url: 'https://media.example/clip.m3u8'}},
          {link: {url: 'javascript:alert(1)'}}, {link: {url: 'http://media.example/plain.mp4'}},
          {link: {url: 'https://media.example/clip.m3u8'}}]};
    }
  };
  const context = {URL, Promise, Date, location: {origin: options.origin || 'https://pan.xunlei.com'},
    document: {addEventListener(name, callback) { handlers[name] = callback; }},
    window: {$nuxt: {$store: {state: {users: {curUser: {userId: options.loggedOut ? '0' : 'fixture-user'}}, safebox: options.safe || {}}}}},
  };
  context.window.webpackJsonp = {push(chunk) {
    const id = Object.keys(chunk[1])[0];
    chunk[1][id]({}, {}, () => options.changed ? {} : {d: api});
  }};
  vm.runInNewContext(code, context);
  async function request(action, args) {
    context.window.ThorCloud.request('fixture', action, args || {});
    await new Promise(resolve => setImmediate(resolve));
    return JSON.parse(context.window.ThorCloud.take('fixture'));
  }
  return {context, calls, handlers, request};
}
(async () => {
  let f = fixture(), value = await f.request('list', {parent: 'folder', page: 'page'});
  assert(value.ok && value.data.files[0].folder && value.data.next === 'next'); checks++;
  assert(f.calls[0][1].parent_id === 'folder' && f.calls[0][1].page_token === 'page'); checks++;
  assert(f.calls[0][1].order === 'MODIFY_TIME_DESC'); checks++;
  assert(!JSON.stringify(value).includes('fixture-user')); checks++;
  assert(f.context.window.ThorCloud.take('fixture') === null); checks++;
  value = await f.request('play', {id: 'video'});
  assert(value.ok && value.data.streams.length === 2); checks++;
  assert(f.calls[1][1].join('|') === 'video|||PLAY'); checks++;
  assert(value.data.streams[1].mime === 'application/x-mpegURL'); checks++;
  let paused = false, target = {tagName: 'VIDEO', muted: false, pause() { paused = true; }};
  f.handlers.play({target}); assert(paused && target.muted); checks++;
  f = fixture({loggedOut: true}); value = await f.request('list');
  assert(!value.ok && !f.calls.length && /登录/.test(value.error)); checks++;
  f = fixture({changed: true}); value = await f.request('list');
  assert(!value.ok && /接口/.test(value.error)); checks++;
  f = fixture({media: {error: 'forbidden', error_description: 'not permitted'}});
  value = await f.request('play', {id: 'video'}); assert(!value.ok && value.error === 'not permitted'); checks++;
  f = fixture({media: {id: 'video', medias: []}}); value = await f.request('play', {id: 'video'});
  assert(!value.ok && /播放地址/.test(value.error)); checks++;
  f = fixture({failure: {error_code: 3, error_description: '超级保险箱需要输入密码'}}); value = await f.request('play', {id: 'video'});
  assert(!value.ok && /账号页面完成保险箱验证/.test(value.error)); checks++;
  f = fixture({safe: {isIn: true, token: 'fixture-only-safe-token'}}); value = await f.request('play', {id: 'video', space: 'SPACE_SAFE'});
  assert(f.calls[0][1][1] === 'SPACE_SAFE' && f.calls[0][1][2] === 'fixture-only-safe-token'); checks++;
  assert(!JSON.stringify(value).includes('fixture-only-safe-token')); checks++;
  f = fixture(); value = await f.request('identity'); assert(value.ok && value.data.logged_in); checks++;
  f = fixture(); f.context.window.ThorCloud.request('fixture', 'list', {}); f.context.window.$nuxt.$store.state.users.curUser.userId = 'another-account'; await new Promise(resolve => setImmediate(resolve)); value = JSON.parse(f.context.window.ThorCloud.take('fixture')); assert(!value.ok && /账号已变化/.test(value.error)); checks++;
  f = fixture({origin: 'https://untrusted.example'}); assert(!f.context.window.ThorCloud); checks++;
  f = fixture({list: {files: [
    {id: 'new-folder', kind: 'drive#folder', modified_time: '2026-10-27T08:00:00.123Z'},
    {id: 'old-video', name: 'movie.mkv', modified_time: 'invalid', created_time: '2026-10-26T09:00:00Z'},
    {id: 'unknown', name: 'clip.MOV'}], next_page_token: 'date-page-2'}});
  value = await f.request('list');
  assert(value.data.files[0].modified_ms === Date.parse('2026-10-27T08:00:00.123Z')); checks++;
  assert(value.data.files[1].modified_ms === 0 && value.data.files[1].created_ms === Date.parse('2026-10-26T09:00:00Z')); checks++;
  assert(value.data.files[2].modified_ms === 0 && value.data.files[2].created_ms === 0); checks++;
  await f.request('list', {page: value.data.next});
  assert(f.calls[1][1].page_token === 'date-page-2' && f.calls[1][1].order === 'MODIFY_TIME_DESC'); checks++;
  console.log(JSON.stringify({passed: true, checks, network_requests: 0, account_writes: false}));
})().catch(error => { console.error(error); process.exitCode = 1; });
