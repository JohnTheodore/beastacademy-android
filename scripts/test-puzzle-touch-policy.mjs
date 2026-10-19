// Requires Playwright and Chromium. Optional PLAYWRIGHT_MODULE and CHROMIUM_PATH overrides.
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE || 'playwright');
const source = await readFile(new URL('../app/src/debug/assets/puzzle-touch-policy.js', import.meta.url), 'utf8');
const browser = await chromium.launch({ headless: true, ...(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {}) });
try {
  const context = await browser.newContext({ viewport: { width: 800, height: 600 }, isMobile: true, hasTouch: true });
  const page = await context.newPage();
  const cdp = await context.newCDPSession(page);
  await page.setContent(`<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">
    <style>body{margin:0;height:2000px}canvas{position:absolute;left:40px;top:80px;width:320px;height:220px;background:#def}button{position:absolute;left:500px;top:20px}</style>
    <canvas id="decoration" style="top:800px"></canvas><button>Outside button</button><script>
    window.probe={moves:0,maxTouches:0,active:[],taps:0,mouseDowns:0,outsideClicks:0,starts:[]};
    document.querySelector('button').onclick=()=>probe.outsideClicks++;
    document.addEventListener('mousedown',()=>probe.mouseDowns++);
    for(const type of ['touchstart','touchmove','touchend','touchcancel'])document.addEventListener(type,e=>{
      probe.active=[...e.touches].map(t=>t.identifier);probe.maxTouches=Math.max(probe.maxTouches,e.touches.length);
      if(type==='touchstart')probe.starts.push({prevented:e.defaultPrevented,ids:probe.active.slice()});
      if(e.target.id==='puzzle'){
        if(type==='touchstart'&&e.touches.length===1)window.startX=e.touches[0].clientX;
        if(type==='touchmove'&&e.touches.length===1){probe.moves++;probe.x=e.touches[0].clientX;}
        if(type==='touchend'&&e.touches.length===0&&Math.abs(e.changedTouches[0].clientX-window.startX)<10)probe.taps++;
      }
    },{passive:true});
    </script>`);
  await page.evaluate(source => { window.install = (0, eval)(source); window.policy = install(); }, source);
  assert.equal(await page.evaluate(() => install() === policy), true);
  assert.equal(await page.evaluate(() => getComputedStyle(document.querySelector('#decoration')).touchAction), 'auto');
  await page.evaluate(() => {
    const canvas = document.createElement('canvas'); canvas.id = 'puzzle';
    canvas.setAttribute('data-beast-gesture-surface', ''); canvas.style.setProperty('touch-action', 'pan-y', 'important');
    document.body.append(canvas);
  });
  assert.equal(await page.evaluate(() => policy.summary().surfaces), 1);
  const touch = (type, points) => cdp.send('Input.dispatchTouchEvent', { type, touchPoints: points.map(([id,x,y]) => ({id,x,y})) });
  await touch('touchStart', [[7,100,150]]);
  for (let x = 120; x <= 460; x += 20) await touch('touchMove', [[7,x,150]]);
  await touch('touchEnd', []);
  const drag = await page.evaluate(() => ({...probe, scrollY}));
  assert.ok(drag.moves > 0); assert.equal(drag.x, 460); assert.deepEqual(drag.active, []);
  assert.equal(drag.scrollY, 0); assert.equal(drag.mouseDowns, 0); assert.equal(drag.starts.at(-1).prevented, true);
  await page.touchscreen.tap(150,150);
  assert.equal(await page.evaluate(() => probe.taps), 1);
  await touch('touchStart', [[1,100,150]]);
  await touch('touchStart', [[1,100,150],[2,180,150]]);
  await touch('touchMove', [[1,80,130],[2,220,180]]);
  await touch('touchEnd', [[2,220,180]]);
  await touch('touchEnd', []);
  assert.equal(await page.evaluate(() => probe.maxTouches), 2);
  assert.deepEqual(await page.evaluate(() => probe.active), []);
  assert.deepEqual(await page.evaluate(() => probe.starts.at(-1).ids), [1,2]);
  await page.touchscreen.tap(550,40);
  assert.equal(await page.evaluate(() => probe.outsideClicks), 1);
  await touch('touchStart', [[0,600,450]]);
  for (let y=410;y>=110;y-=30) await touch('touchMove', [[0,600,y]]);
  await touch('touchEnd', []);
  await page.waitForTimeout(200);
  assert.ok(await page.evaluate(() => scrollY > 0), 'Outside scrolling must remain available');
  await page.evaluate(() => {
    window.surface = document.querySelector('#puzzle'); surface.remove();
  });
  assert.equal(await page.evaluate(() => policy.summary().surfaces), 0);
  assert.equal(await page.evaluate(() => surface.style.touchAction), 'pan-y');
  await page.evaluate(() => document.body.append(surface));
  assert.equal(await page.evaluate(() => policy.summary().surfaces), 1);
  await page.evaluate(() => document.body.prepend(surface));
  assert.equal(await page.evaluate(() => policy.summary().surfaces), 1);
  await page.evaluate(() => { policy.undo(); scrollTo(0,0); });
  assert.deepEqual(await page.evaluate(() => [surface.style.touchAction, surface.style.getPropertyPriority('touch-action')]), ['pan-y','important']);
  await page.touchscreen.tap(150,150);
  assert.equal(await page.evaluate(() => probe.starts.at(-1).prevented), false);
  console.log('PASS: trusted drag beyond canvas, touch tap, genuine multitouch, outside button/scroll, dynamic surfaces, idempotency, and undo.');
} finally { await browser.close(); }
