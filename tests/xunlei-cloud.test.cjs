const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('assets/xunlei-cloud.js', 'utf8');
let checks = 0;

function page(cache = {}, onTimer = () => {}, origin = 'https://pan.xunlei.com') {
  let clock = 0;
  const factories = {}, invoked = [];
  function requireModule(id) {
    invoked.push(String(id));
    if (cache[id]) return cache[id].exports;
    const module = cache[id] = {exports: {}};
    if (!factories[id]) throw new Error('Unexpected factory');
    factories[id](module, module.exports, requireModule);
    return module.exports;
  }
  requireModule.c = cache; requireModule.m = factories;
  factories[10] = () => { throw new Error('Unrelated module must never be evaluated'); };
  const window = {$nuxt: {$store: {state: {users: {curUser: {userId: 'fixture-owner'}}}}}, webpackJsonp: {
    push(value) { Object.assign(factories, value[1]); value[2].forEach(entry => requireModule(entry[0])); }
  }};
  class Clock extends Date { static now() { return clock; } }
  vm.runInNewContext(source, {window, location: {origin}, URL, Date: Clock, document: {addEventListener() {}},
    setTimeout(callback, ms) { clock += ms; onTimer(clock, window, cache); return setTimeout(callback, 0); }});
  return {window, invoked, cache, time: () => clock};
}
async function request(context, action, args = {}, id = 1) {
  context.window.ThorCloud.request(id, action, args);
  for (let attempt = 0; attempt < 200; attempt++) {
    await new Promise(resolve => setTimeout(resolve, 1));
    const result = context.window.ThorCloud.take(id);
    if (result) return JSON.parse(result);
  }
  throw new Error('Fixture did not complete');
}
function service() {
  return {lists: [], plays: [],
    async findFiles(args, authorization) { this.lists.push({args, authorization}); return {files: [{id: 'fixture-file', name: 'fixture.mp4', kind: 'drive#file', mime_type: 'video/mp4'}], next_page_token: 'next'}; },
    async getFileInfo(...args) { this.plays.push(args); return {id: 'fixture-file', name: 'fixture.mp4', web_content_link: 'https://fixture.invalid/original', medias: [{link: {url: 'https://fixture.invalid/original'}}, {link: {url: 'http://fixture.invalid/rejected'}}, {link: {url: 'https://fixture.invalid/transcode'}, quality: '720P'}]}; }
  };
}
(async () => {
  const api = service(), relocated = page({4319: {exports: {default: {client: api}}}});
  let result = await request(relocated, 'list', {parent: 'folder', page: 'cursor'});
  assert.equal(result.ok, true); assert.equal(result.data.files[0].id, 'fixture-file');
  assert.equal(api.lists[0].args.order, 'MODIFY_TIME_DESC'); assert.equal(api.lists[0].args.page_token, 'cursor'); checks++;
  assert(!relocated.invoked.includes('10')); checks++;
  result = await request(relocated, 'play', {id: 'fixture-file', space: ''}, 2);
  assert.equal(result.data.streams.length, 2); assert.deepEqual(api.plays[0], ['fixture-file', '', '', 'PLAY']); checks++;
  assert.equal(relocated.invoked.length, 1); checks++;

  const delayedApi = service(), delayed = page({}, (clock, window, cache) => { if (clock >= 750) cache[72] = {exports: {z: delayedApi}}; });
  result = await request(delayed, 'list'); assert.equal(result.ok, true); assert.equal(delayedApi.lists.length, 1); assert(delayed.time() >= 750); checks++;
  let wrongCalls = 0;
  const partial = page({2: {exports: {findFiles() { wrongCalls++; }}}}, (clock, window, cache) => { cache[92] = {exports: {a: service()}}; });
  result = await request(partial, 'list'); assert.equal(result.ok, true); assert.equal(wrongCalls, 0); checks++;

  const absent = page(); result = await request(absent, 'list'); assert.equal(result.ok, false); assert.match(result.error, /刷新/); assert(absent.time() >= 6000 && absent.time() <= 6250); checks++;
  const changedApi = service(), changed = page({}, (clock, window, cache) => { window.$nuxt.$store.state.users.curUser.userId = 'another-fixture-owner'; cache[80] = {exports: {d: changedApi}}; });
  result = await request(changed, 'list'); assert.equal(result.ok, false); assert.match(result.error, /账号已变化/); assert.equal(changedApi.lists.length, 0); checks++;
  const otherOrigin = page({}, () => {}, 'https://pan.xunlei.com.invalid'); assert.equal(otherOrigin.window.ThorCloud, undefined); checks++;
  console.log(`Official cloud adapter: ${checks} cases passed`);
})().catch(error => { console.error(error); process.exitCode = 1; });
