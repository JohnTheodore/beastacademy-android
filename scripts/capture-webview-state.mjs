// Read-only CDP observations. No Input, Page navigation/reload, listener installation, or recovery.
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';
const [base, output] = process.argv.slice(2);
if (!base || !output) throw Error('Usage: node capture-webview-state.mjs HTTP_BASE OUTPUT_DIRECTORY');
const get = async path => {
  const response = await fetch(new URL(path, base), { signal: AbortSignal.timeout(3000) });
  if (!response.ok) throw Error(`HTTP ${response.status} for ${path}`);
  return response.json();
};
const save = (name, value) => writeFile(join(output, name), JSON.stringify(value, null, 2) + '\n', { mode: 0o600 });
const pages = await get('/json/list');
await save('cdp-targets.json', pages);
try { await save('cdp-version.json', await get('/json/version')); } catch (error) { console.log(String(error)); }
for (const page of pages.filter(page => page.type === 'page' && /^https:\/\/(?:[a-z0-9-]+\.)*beastacademy\.com(?:\/|$)|^https:\/\/inputlab\.invalid(?:\/|$)|^https:\/\/login\.artofproblemsolving\.com(?:\/|$)/i.test(page.url))) {
  const url = new URL(page.webSocketDebuggerUrl);
  const local = new URL(base); url.hostname = local.hostname; url.port = local.port;
  const socket = new WebSocket(url);
  const pending = new Map(); let sequence = 0;
  const call = (method, params) => new Promise((resolve, reject) => {
    const id = ++sequence;
    const timeout = setTimeout(() => { pending.delete(id); reject(Error(`${method} timed out`)); }, 3000);
    pending.set(id, message => { clearTimeout(timeout); message.error ? reject(Error(JSON.stringify(message.error))) : resolve(message.result); });
    socket.send(JSON.stringify({ id, method, params }));
  });
  socket.onmessage = event => {
    const message = JSON.parse(event.data);
    if (message.id) { const resolve = pending.get(message.id); pending.delete(message.id); resolve?.(message); }
  };
  try {
    await new Promise((resolve, reject) => {
      const timeout = setTimeout(() => reject(Error('CDP connection timed out')), 3000);
      socket.onopen = () => { clearTimeout(timeout); resolve(); };
      socket.onerror = () => { clearTimeout(timeout); reject(Error('CDP connection failed')); };
    });
    const state = await call('Runtime.evaluate', {
      expression: `({timeOrigin:performance.timeOrigin,wall:Date.now(),url:location.origin+location.pathname,
        visibility:document.visibilityState,focused:document.hasFocus(),ready:document.readyState,
        lastTouch:window.__beastInputTraceLastTouch||null,
        inputCompatibility:window.__beastInputCompatStatus||null,
        canvasCount:document.querySelectorAll('canvas').length})`,
      returnByValue: true, throwOnSideEffect: true, silent: true, timeout: 2000, disableBreaks: true
    });
    await save(`cdp-state-${page.id.replace(/[^a-z0-9_-]/gi,'_')}.json`, state);
  } catch (error) { console.log(`Target ${page.id}: ${error}`); }
  finally { socket.close(); }
}
