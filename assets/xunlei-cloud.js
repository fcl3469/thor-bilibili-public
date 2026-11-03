/* Thor-owned adapter for the official cloud website. Credentials stay in its WebView. */
(function () {
  'use strict';
  if (location.origin !== 'https://pan.xunlei.com' || window.ThorCloud) return;
  var answers = Object.create(null), api, requireModule, awaitingClient, sequence = 0;
  function runtime() {
    if (requireModule) return requireModule;
    if (!window.webpackJsonp || typeof window.webpackJsonp.push !== 'function') return null;
    var id = 'thor_cloud_' + Date.now() + '_' + (++sequence), modules = {};
    modules[id] = function (module, exports, require) { requireModule = require; };
    try { window.webpackJsonp.push([[id], modules, [[id]]]); } catch (_) { return null; }
    if (requireModule) {
      if (requireModule.c) delete requireModule.c[id];
      if (requireModule.m) delete requireModule.m[id];
    }
    return requireModule;
  }
  function availableClient() {
    var require = runtime(), seen = new WeakSet(), visited = 0;
    if (!require || !require.c) return null;
    function find(value, depth) {
      if (!value || (typeof value !== 'object' && typeof value !== 'function') || seen.has(value) || ++visited > 2048) return null;
      seen.add(value);
      try { if (typeof value.findFiles === 'function' && typeof value.getFileInfo === 'function') return value; } catch (_) {}
      if (depth >= 2) return null;
      var keys;
      try { keys = Object.keys(value).slice(0, 32); } catch (_) { return null; }
      for (var i = 0; i < keys.length; i++) {
        try { var found = find(value[keys[i]], depth + 1); if (found) return found; } catch (_) {}
      }
      return null;
    }
    var ids = Object.keys(require.c).slice(0, 5000);
    for (var i = 0; i < ids.length; i++) {
      var found = find(require.c[ids[i]].exports, 0);
      if (found) return found;
    }
    return null;
  }
  async function client() {
    if (api) return api;
    if (awaitingClient) return awaitingClient;
    awaitingClient = (async function () {
      var deadline = Date.now() + 6000;
      do {
        var candidate = availableClient();
        if (candidate) return (api = candidate);
        if (Date.now() >= deadline) break;
        await new Promise(function (resolve) { setTimeout(resolve, 250); });
      } while (true);
      throw new Error('暂时无法读取云盘，请点刷新重试');
    })();
    try { return await awaitingClient; } finally { awaitingClient = null; }
  }
  function loggedIn() {
    var state = window.$nuxt && window.$nuxt.$store && window.$nuxt.$store.state;
    var user = state && state.users && state.users.curUser;
    return !!(user && user.userId && String(user.userId) !== '0');
  }
  function account() {
    var state = window.$nuxt && window.$nuxt.$store && window.$nuxt.$store.state;
    var user = state && state.users && state.users.curUser;
    return user && user.userId ? String(user.userId) : '0';
  }
  function timestamp(value) {
    var time = Date.parse(value || '');
    return Number.isFinite(time) && time > 0 ? time : 0;
  }
  function fileSummary(file) {
    return {id: String(file.id || ''), name: String(file.name || '未命名'),
      folder: file.kind === 'drive#folder', size: String(file.size || '0'),
      parent: String(file.parent_id || ''), mime: String(file.mime_type || ''),
      phase: String(file.phase || ''), space: String(file.space || ''),
      thumbnail: secure(file.thumbnail_link) ? file.thumbnail_link : '',
      locked: file.folder_type === 'SAFE' || /SAFE/.test(String(file.space || '')),
      category: String(file.file_category || ''), duration_ms: Number(file.duration_ms || 0),
      modified_ms: timestamp(file.modified_time), created_ms: timestamp(file.created_time)};
  }
  function secure(url) {
    try { return new URL(url).protocol === 'https:'; } catch (_) { return false; }
  }
  async function run(action, args, owner) {
    if (action === 'identity') return {logged_in: loggedIn(), account_id: account()};
    if (!loggedIn()) throw new Error('请先登录迅雷账号，再刷新云盘');
    var c = await client(), result;
    if (owner !== account()) throw new Error('迅雷账号已变化，请重新刷新云盘');
    var safe = window.$nuxt.$store.state.safebox || {};
    var spaceAuthorization = safe.isIn ? (safe.token || '') : '';
    if (action === 'list') {
      result = await c.findFiles({parent_id: args.parent || '', space: args.space || '', limit: 100,
        page_token: args.page || '', filters: {trashed: {eq: false}},
        thumbnail_size: 'SIZE_SMALL', with_audit: true, order: 'MODIFY_TIME_DESC'}, spaceAuthorization);
      if (result.error) throw new Error(result.error_description || '读取文件列表失败');
      return {files: (result.files || []).map(fileSummary), next: result.next_page_token || ''};
    }
    if (action === 'play') {
      result = await c.getFileInfo(args.id, args.space || '', spaceAuthorization, 'PLAY');
      if (result.error) throw new Error(result.error_description || '获取播放地址失败');
      var streams = [], seen = Object.create(null);
      function add(url, label, mime) {
        if (!secure(url) || seen[url]) return;
        seen[url] = true;
        streams.push({url: url, label: label, mime: mime || ''});
      }
      add(result.web_content_link, '原文件', result.mime_type);
      (result.medias || []).forEach(function (media) {
        if (!media.link || !media.link.url) return;
        var mime = media.mime_type || media.link.mime_type || '';
        var label = media.media_name || media.name || media.quality || '云端转码';
        add(media.link.url, String(label), mime);
      });
      if (!streams.length) throw new Error('这个文件尚未返回可播放地址；请确认已转存完成、账号有播放权限');
      return {id: String(result.id || args.id), name: String(result.name || args.name || '云盘视频'), space: args.space || '',
        mime: String(result.mime_type || args.mime || ''), size: String(result.size || args.size || '0'),
        duration_ms: Number(result.duration_ms || 0), streams: streams};
    }
    throw new Error('未知云盘操作');
  }
  window.ThorCloud = {
    request: function (id, action, args) {
      var owner = account();
      Promise.resolve().then(function () { return run(action, args || {}, owner); }).then(
        function (data) {
          if (owner !== account()) { answers[id] = JSON.stringify({ok: false, error: '迅雷账号已变化，请重新刷新云盘'}); return; }
          answers[id] = JSON.stringify({ok: true, data: data});
        },
        function (error) {
          var message = String(error && (error.error_description || error.message || error.error) || '云盘请求失败');
          if (message.indexOf('保险箱') >= 0) message = '请先在账号页面完成保险箱验证，再重新选择视频。';
          answers[id] = JSON.stringify({ok: false, error: message});
        });
    },
    take: function (id) {
      var answer = answers[id] || null;
      delete answers[id];
      return answer;
    }
  };
  // The website supplies login and file management; video belongs to Thor's player.
  document.addEventListener('play', function (event) {
    if (event.target && /^(VIDEO|AUDIO)$/.test(event.target.tagName)) {
      event.target.muted = true;
      event.target.pause();
    }
  }, true);
})();
