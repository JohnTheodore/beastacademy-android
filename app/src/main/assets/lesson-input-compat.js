/* Qualified v12.119.0 input compatibility. Installed before each document.
 * Install at document start, before the site's webpack runtime. It changes only
 * the two qualified Banvas handlers' contact lists and single-piece ownership.
 * Per-engine single-contact ownership; no guessed tool-state rollback on cancel.
 */
(function installLessonInputCompat() {
  'use strict';
  const key = '__beastInputCompat';
  if (window[key]) return window[key];
  if (location.origin !== 'https://beastacademy.com' || window !== window.top)
    return { status: 'unsupported-origin-or-frame' };
  if (document.readyState !== 'loading' || window.webpackChunkba)
    return { status: 'too-late' };

  const signatures = {
    54145: ['497:74dfca55:f8517db3', '114:65e11b0e:344bc19c', '594:f1adb855:17c409f7'],
    55417: ['507:91609954:97764302', '189:6308f9e3:34e0fec5', '598:928b0737:77ae1a35']
  };
  const mouseNames = ['resetMouseDownView', 'onTouchStart', 'onTouchMove',
    'onGlobalTouchEnd', 'onGlobalTouchMove', 'setLongHoverTimeout'];
  const mouseSignatures = {
    54145: ['85:8d8ab4c3:24f34775','432:dd55e616:27f8ee74','896:8ac06e1c:c156951e',
      '579:d299f2d0:1436712e','493:fcc9d644:3f17e59a','174:e3f98d96:4e4e1cd4'],
    55417: ['85:8d8ab4c3:24f34775','392:ee5e60d8:631cd38a','826:ab25c8a3:84ca62b9',
      '539:8dc9a144:27fab6c2','463:409fa523:8e14a71d','174:e3f98d96:4e4e1cd4']
  };
  const checkedMice = new WeakMap();
  function qualifiedMouse(mouse, id) {
    if (!mouse || !mouseSignatures[id]) return false;
    const methods = mouseNames.map(name => mouse[name]);
    const cached = checkedMice.get(mouse);
    if (cached?.id === id && methods.every((method, i) => method === cached.methods[i])) return cached.valid;
    const valid = methods.every((method, i) => typeof method === 'function' &&
      fingerprint(method) === mouseSignatures[id][i]);
    checkedMice.set(mouse, {methods, valid, id});
    if (!valid) { engines[id] = 'unsupported-mouse-signature'; report(); }
    return valid;
  }
  // Compatibility fingerprints, not authentication of hostile code.
  function fingerprint(fn) {
    const source = Function.prototype.toString.call(fn);
    let a = 2166136261, b = 3339675911;
    for (let i = 0; i < source.length; i++) {
      a = Math.imul(a ^ source.charCodeAt(i), 16777619);
      b = Math.imul(b ^ source.charCodeAt(i), 2246822519);
    }
    return [source.length, (a >>> 0).toString(16), (b >>> 0).toString(16)].join(':');
  }
  const active = new Set(), retired = new Set(), snapshots = new WeakMap();
  const hooks = [], restores = [];
  const owners = new Map();
  let ownerFiltered = 0, interrupted = 0;
  let enabled = true, adapted = 0, ignored = 0, evicted = 0;
  const engines = {};
  function report() {
    const summary = {type: 'input-compat', version: 1, document: performance.timeOrigin,
      engines: {...engines}, adapted, ignored, ownerFiltered, interrupted, enabled};
    window.__beastInputCompatStatus = summary;
    try { window.BeastInputTrace?.postMessage(JSON.stringify(summary)); } catch (_) {}
  }
  function interrupt(engine) {
    owners.delete(engine);
    const mouse = engine.banvasMouse;
    if (mouse && qualifiedMouse(mouse, checkedMice.get(mouse)?.id)) {
      // Only engine bookkeeping, never a tool's completion callback or answer.
      // Tool-specific transient state is deliberately not guessed or rolled back.
      clearTimeout(mouse.longHoverTimeoutID);
      mouse.longHoverTimeoutID = null;
      mouse.mouseDownView = null;
      mouse.isDragging = false;
      mouse.isClickDragging = false;
      mouse.tooFarToClickDrag = false;
      mouse.mouseMoveViews = [];
    }
    interrupted++;
    report();
  }
  function observe(event) {
    if (!enabled || !event.isTrusted) return;
    const changed = Array.from(event.changedTouches || []);
    if (!changed.length) return;
    if (event.type === 'touchstart') {
      for (const touch of changed) {
        // An explicitly observed new start always wins over a retired ID.
        retired.delete(touch.identifier);
        if (active.size < 64) active.add(touch.identifier);
      }
    }
    const stale = new Set();
    for (const name of ['touches', 'targetTouches', 'changedTouches'])
      for (const touch of Array.from(event[name] || []))
        if (retired.has(touch.identifier)) stale.add(touch.identifier);
    snapshots.set(event, stale);
    if (event.type === 'touchcancel') {
      for (const [engine, owner] of owners) {
        if (changed.some(t => t.identifier === owner)) {
          interrupt(engine);
        }
      }
    }
    if (event.type === 'touchend' || event.type === 'touchcancel') {
      for (const touch of changed) {
        // Never infer that an unobserved or merely absent contact has ended.
        if (!active.delete(touch.identifier)) continue;
        retired.add(touch.identifier);
        if (retired.size > 256) {
          retired.delete(retired.values().next().value);
          evicted++;
        }
      }
    }
  }
  const types = ['touchstart', 'touchmove', 'touchend', 'touchcancel'];
  for (const type of types) window.addEventListener(type, observe, {capture: true, passive: true});

  function qualifiedScript(file) {
    const urls = Array.from(document.scripts, script => script.src).filter(Boolean)
      .map(src => new URL(src, location.href)).filter(url => url.pathname.endsWith('/' + file));
    return urls.length > 0 && urls.every(url => url.origin === location.origin &&
      url.pathname === '/dist/v12.119.0/' + file);
  }
  function patch(exports, id) {
    if (!qualifiedScript('bundle_core.js') || !qualifiedScript('bundle_vendor.js')) {
      engines[id] = 'unsupported-deployment';
      report();
      return;
    }
    const prototype = (id === 54145 ? exports : exports?.BanvasElement)?.prototype;
    const names = ['onMouseEvent', 'getXYFromEvent', 'listenToInputEvents'];
    if (!prototype || !names.every((name, i) => typeof prototype[name] === 'function' &&
        fingerprint(prototype[name]) === signatures[id][i])) {
      engines[id] = 'unsupported-signature';
      report();
      return;
    }
    const original = prototype.onMouseEvent;
    const getXY = prototype.getXYFromEvent;
    const listen = prototype.listenToInputEvents;
    const descriptor = Object.getOwnPropertyDescriptor(prototype, 'onMouseEvent');
    const wrapped = function(event, handlerName) {
      if (!enabled || !event.isTrusted || !event.type.startsWith('touch'))
        return original.call(this, event, handlerName);
      // A reused host can acquire instance/prototype overrides after creation.
      // Check dependencies again before adapting input, without hashing per move.
      if (this.getXYFromEvent !== getXY || this.listenToInputEvents !== listen) {
        owners.delete(this);
        if (engines[id] !== 'unsupported-engine-replacement') {
          engines[id] = 'unsupported-engine-replacement';
          report();
        }
        return original.call(this, event, handlerName);
      }
      if (!qualifiedMouse(this.banvasMouse, id)) {
        owners.delete(this);
        return original.call(this, event, handlerName);
      }
      const stale = snapshots.get(event);
      // No observation means no authority to reinterpret this event.
      if (!stale) return original.call(this, event, handlerName);
      if (event.type === 'touchstart' && owners.has(this)) {
        const previous = owners.get(this);
        // A new start for the same ID is a new operation even if an earlier
        // terminal event was lost; never finish the abandoned operation.
        if (!active.has(previous) || Array.from(event.changedTouches).some(t => t.identifier === previous))
          interrupt(this);
      }
      const owner = owners.get(this);
      const start = event.type === 'touchstart';
      const end = event.type === 'touchend';
      if (start && owner !== undefined) { ownerFiltered++; return; }
      if (!start && owner === undefined) { ownerFiltered++; return; }
      const values = {};
      let changed = false;
      for (const name of ['touches', 'targetTouches', 'changedTouches']) {
        const raw = Array.from(event[name] || []);
        const list = raw.filter(t => !stale.has(t.identifier) &&
          (owner === undefined || t.identifier === owner));
        changed ||= list.length !== raw.length;
        list.item = index => list[index] || null;
        values[name] = list;
      }
      if (!values.changedTouches.length) { ignored++; return; }
      // Preserve the vendor's existing policy for an ambiguous initial press.
      // Only an already-started single-piece gesture gains owner isolation.
      if (start && (values.touches.length !== 1 || values.changedTouches.length !== 1))
        return;
      let delivered = event;
      if (changed) {
        adapted++;
        if (adapted === 1) report();
        delivered = new Proxy(event, {
          get(target, property) {
            if (Object.prototype.hasOwnProperty.call(values, property)) return values[property];
            const value = Reflect.get(target, property, target);
            return typeof value === 'function' ? value.bind(target) : value;
          }
        });
      }
      try {
        const result = original.call(this, delivered, handlerName);
        if (start && this.banvasMouse?.mouseDownView && !this.banvasMouse.isClickDragging)
          owners.set(this, values.changedTouches[0].identifier);
        return result;
      } finally {
        // The canvas handler is a no-op for end; the document handler completes.
        if (end && handlerName === 'onGlobalTouchEnd') owners.delete(this);
      }
    };
    Object.defineProperty(prototype, 'onMouseEvent', {
      value: wrapped, configurable: true, writable: true,
      enumerable: descriptor?.enumerable ?? false
    });
    restores.push(() => {
      if (prototype.onMouseEvent !== wrapped) return; // Do not overwrite a later patch.
      if (descriptor) Object.defineProperty(prototype, 'onMouseEvent', descriptor);
      else delete prototype.onMouseEvent;
    });
    engines[id] = 'patched';
    report();
  }

  function hook(require, id) {
    const modules = require.m;
    const previous = Object.getOwnPropertyDescriptor(modules, id);
    if (previous && (!previous.configurable || !('value' in previous))) {
      engines[id] = 'unsupported-module-property';
      return;
    }
    let raw, wrapped;
    const set = factory => {
      raw = factory;
      wrapped = typeof factory !== 'function' ? factory : function(module, ...args) {
        const result = factory.call(this, module, ...args);
        if (enabled) {
          try { patch(module.exports, id); }
          catch (_) { engines[id] = 'patch-failed'; report(); }
        }
        return result;
      };
    };
    set(previous?.value);
    const get = () => wrapped;
    Object.defineProperty(modules, id, { configurable: true, enumerable: true, get, set });
    hooks.push(() => {
      if (Object.getOwnPropertyDescriptor(modules, id)?.get !== get) return;
      if (raw === undefined && !previous) delete modules[id];
      else Object.defineProperty(modules, id, {
        value: raw, writable: true, enumerable: true, configurable: true
      });
    });
    engines[id] = 'awaiting-module';
  }
  const forgetOwners = () => { for (const engine of owners.keys()) interrupt(engine); };
  const removals = new MutationObserver(() => {
    for (const engine of owners.keys()) {
      if (!engine.canvas?.isConnected) interrupt(engine);
    }
  });
  removals.observe(document, {childList: true, subtree: true});
  const hidden = () => { if (document.hidden) forgetOwners(); };
  window.addEventListener('blur', forgetOwners);
  window.addEventListener('pagehide', forgetOwners);
  document.addEventListener('visibilitychange', hidden);
  const state = {
    summary: () => ({ enabled, engines: {...engines}, active: active.size,
      retired: retired.size, adapted, ignored, evicted, ownerFiltered, interrupted, owners: owners.size,
      version: 1, document: performance.timeOrigin }),
    undo: () => {
      if (active.size) return false; // Never change delivery during a known gesture.
      enabled = false;
      for (const restore of restores.reverse()) restore();
      for (const restore of hooks.reverse()) restore();
      for (const type of types) window.removeEventListener(type, observe, true);
      window.removeEventListener('blur', forgetOwners);
      window.removeEventListener('pagehide', forgetOwners);
      document.removeEventListener('visibilitychange', hidden);
      retired.clear();
      owners.clear();
      removals.disconnect();
      report();
      return true;
    }
  };
  window[key] = state;
  // Webpack invokes this runtime before its entrypoint. No polling and no eager
  // require(): vendor modules retain their original initialization order.
  window.webpackChunkba = [[['beast-input-compat-v1'], {}, require => {
    if (!enabled) return;
    try { for (const id of [54145, 55417]) hook(require, id); }
    catch (_) { engines.bootstrap = 'unsupported-runtime'; }
    report();
  }]];
  report();
  return state;
})
