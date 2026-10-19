// Usage: node scripts/test-retired-touch-guard.mjs PRIVATE_AUDIT_DIRECTORY
// Uses pinned full vendor bundles privately; never fetches from the live site.
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import path from 'node:path';
const root = process.argv[2];
if (!root) throw new Error('Supply the private v12.119.0 audit directory');
const {chromium} = await import(process.env.PLAYWRIGHT_MODULE || 'playwright');
const patch = await readFile(new URL('../app/src/main/assets/lesson-input-compat.js', import.meta.url), 'utf8');
const hashes = {
  'bundle_core.js': '10f2992e52b4347d5ca699d40ae272a4b7d0a6d5703c6f59666ec8a32abc8ce4',
  'bundle_vendor.js': '72336f21d30d70e5c9ce2ced6d596f9d060739b2405de688d5e1c74c766cc61d'
};
const bundles = {};
for (const [name, hash] of Object.entries(hashes)) {
  bundles[name] = await readFile(path.join(root, name), 'utf8');
  assert.equal(createHash('sha256').update(bundles[name]).digest('hex'), hash, `Unqualified ${name}`);
}
const browser = await chromium.launch({headless: true,
  ...(process.env.CHROMIUM_PATH ? {executablePath: process.env.CHROMIUM_PATH} : {})});
async function setup(id, enabled, {mutated = false, coreFirst = false, deployment = 'v12.119.0', vendorDeployment = deployment} = {}) {
  const page = await browser.newPage({viewport: {width: 800, height: 600}, hasTouch: true});
  const pageErrors = [];
  page.on("pageerror", error => pageErrors.push(error.message));
  // Every request is fulfilled locally, including incidental vendor startup requests.
  await page.route('**/*', route => {
    const name = new URL(route.request().url()).pathname.split('/').pop();
    if (bundles[name]) {
      let body = bundles[name];
      if (mutated && name === 'bundle_core.js') body = body.replace(
        'if(e.currentTarget!==document){var r,n;', 'if(e.currentTarget!==document){var r,n;void 0;');
      return route.fulfill({contentType: 'text/javascript; charset=utf-8', body});
    }
    if (route.request().isNavigationRequest()) {
      const names = coreFirst ? ['bundle_core.js', 'bundle_vendor.js'] : ['bundle_vendor.js', 'bundle_core.js'];
      return route.fulfill({contentType: 'text/html; charset=utf-8', body: `<!doctype html><style>
        body{margin:0}canvas{touch-action:none}</style><canvas width="500" height="350"></canvas>
        <button style="position:absolute;left:600px;top:50px">Outside</button>
        ${names.map(n => `<script src="/dist/${n === 'bundle_vendor.js' ? vendorDeployment : deployment}/${n}"></script>`).join('')}`});
    }
    return route.fulfill({status: 404, body: ''});
  });
  await page.addInitScript(({patch, enabled}) => {
    // Harness-only access to the real observer for modeled corrupt contact lists.
    // No such entry point exists in the patch. These are NOT trusted browser faults.
    window.modelObservers = {};
    const add = window.addEventListener;
    window.addEventListener = function(type, cb, options) {
      if (type.startsWith('touch') && options?.capture) modelObservers[type] = cb;
      return add.call(this, type, cb, options);
    };
    if (enabled) window.guard = (0, eval)(patch)();
    window.addEventListener = add;
    (window.webpackChunkba ||= []).push([['beast-test-runtime'], {}, r => window.testRequire = r]);
  }, {patch, enabled});
  await page.goto('https://beastacademy.com/school/classroom/3/1/342/5');
  // This fixture intentionally lacks the full site's bootstrap. Its entrypoint
  // stops at getDistURL; the original modules and runtime remain available.
  assert.deepEqual(pageErrors, ['globalThis.getDistURL is not a function']);
  await page.evaluate(id => {
    const exported = testRequire(id);
    const Engine = id === 54145 ? exported : exported.BanvasElement;
    const canvas = document.querySelector('canvas');
    window.log = {drags: 0, releases: 0, clicks: 0, outside: 0, raw: []};
    const piece = {
      rect: {x: 0, y: 0, w: 500, h: 350},
      shouldGetMouseEventsAt: () => true, wouldClick: () => false,
      wouldStartClickDrag: () => true, wouldExpand: () => false,
      getHoverCustomCursor: () => false,
      onMouseDown: () => { log.toolMoving=true; },
      onMouseDrag: (...args) => { log.drags++; log.point = args.slice(-2); },
      onMouseRelease: (...args) => { log.toolMoving=false; log.releases++; log.releasePoint=args.slice(-2); }
    };
    // Actual shipped engine methods + actual shipped BanvasMouse; only geometry,
    // view behavior and rendering are fixture substitutes.
    window.engine = Object.create(Engine.prototype);
    Object.assign(engine, {canvas, findViewsAtMouse: () => [piece], getTopRect: () => piece.rect,
      setMouseCursorType: () => {}});
    engine.banvasMouse = engine.setupBanvasMouse();
    engine.listenToInputEvents();
    document.querySelector('button').onclick = () => log.outside++;
    document.addEventListener('touchstart', e => log.raw.push({trusted:e.isTrusted,
      ids:Array.from(e.touches, t=>t.identifier)}), {passive:true});
    window.model = (type, touches, changed, trusted = true) => {
      const point = ([identifier, clientX]) => ({identifier, clientX, clientY:100,
        pageX:clientX, pageY:100, target:canvas});
      const event = {type, touches:touches.map(point), targetTouches:touches.map(point),
        changedTouches:changed.map(point), isTrusted:trusted, target:canvas, currentTarget:canvas,
        preventDefault(){this.prevented=true}, stopPropagation(){this.stopped=true}};
      modelObservers[type]?.(event);
      const suffix = {touchstart:'Start',touchmove:'Move',touchend:'End'}[type];
      if (suffix) {
        engine.onMouseEvent(event, 'onTouch'+suffix);
        if (type !== 'touchstart' && !event.stopped) {
          event.currentTarget=document;
          engine.onMouseEvent(event, 'onGlobalTouch'+suffix);
        }
      }
      return {raw:event.touches.map(t=>t.identifier), changed:event.changedTouches.map(t=>t.identifier)};
    };
    window.snapshot = () => ({...log, held:!!engine.banvasMouse.mouseDownView,
      guard:window.guard?.summary()});
  }, id);
  return page;
}
try {
  console.log('Browser:', browser.version());
  for (const id of [54145, 55417]) {
    for (const enabled of [false, true]) {
      const page = await setup(id, enabled, {coreFirst:id === 55417});
      if (enabled) assert.equal((await page.evaluate(() => guard.summary())).engines[id], 'patched');
      const result = await page.evaluate(() => {
        // First establish genuine observed starts and ends for IDs 1 and 2.
        for (const id of [1,2]) { model('touchstart',[[id,80]],[[id,80]]); model('touchend',[],[[id,80]]); }
        log.drags=0;log.releases=0;
        model('touchstart',[[10,100],[1,80],[2,90]],[[10,100]]);
        const raw=model('touchmove',[[10,140],[1,80],[2,90]],[[10,140]]);
        // A late old-contact end must not release the new owner's piece.
        model('touchend',[[10,140],[2,90]],[[1,80]]);
        const during=snapshot();
        model('touchend',[[1,80],[2,90]],[[10,140]]);
        const after=snapshot();
        // Reusing a previously retired ID must work immediately.
        model('touchstart',[[1,100]],[[1,100]]);
        model('touchmove',[[1,150]],[[1,150]]);
        model('touchend',[],[[1,150]]);
        return {raw,during,after,next:snapshot()};
      });
      assert.deepEqual(result.raw.raw,[10,1,2]); // Raw evidence unchanged.
      if (enabled) {
        assert.ok(result.during.drags>0);
        assert.equal(result.during.held,true);
        assert.equal(result.during.releases,0);
        assert.equal(result.after.held,false);
        assert.equal(result.after.releases,1);
        assert.equal(result.next.releases,2);
      } else assert.equal(result.after.drags,0, 'Original engine must reproduce phantom refusal');
      await page.close();
    }
    const stressPage=await setup(id,true);
    const stress=await stressPage.evaluate(()=>{
      let seed=19761019;
      const rand=()=>{seed=(Math.imul(seed,1664525)+1013904223)>>>0;return seed;};
      const orders={ownerFirst:0,secondaryFirst:0};
      for(let i=0;i<1000;i++){
        const a=10+rand()%10,b=30+rand()%10,x=100+rand()%100;
        const before=log.releases;
        model('touchstart',[[a,100]],[[a,100]]);
        model('touchmove',[[a,x]],[[a,x]]);
        model('touchstart',[[a,x],[b,300]],[[b,300]]);
        model('touchmove',[[a,x],[b,330]],[[b,330]]);
        if(log.point[0]!==x)throw Error('Secondary finger moved owner');
        // The low bit of this LCG alternates and four calls per iteration
        // would select the same branch forever. Assert both orders are covered.
        if((rand()>>>16)&1){
          orders.secondaryFirst++;
          model('touchend',[[a,x]],[[b,330]]);
          if(log.releases!==before)throw Error('Secondary finger released owner');
          model('touchend',[],[[a,x]]);
        }else{
          orders.ownerFirst++;
          model('touchend',[[b,330]],[[a,x]]);
          if(log.releasePoint[0]!==x)throw Error('Wrong owner-release coordinates');
          model('touchend',[],[[b,330]]);
        }
        if(log.releases!==before+1||engine.banvasMouse.mouseDownView)throw Error('Duplicate or missing completion');
      }
      model('touchstart',[[10,100]],[[10,100]]);
      model('touchmove',[[10,140]],[[10,140]]);
      model('touchstart',[[10,100]],[[10,100]]); // Deliberately missing old terminal.
      if(log.releases!==1000)throw Error('Replacement start committed an abandoned drag');
      model('touchmove',[[10,170]],[[10,170]]);
      model('touchend',[],[[10,170]]);
      if(log.releasePoint[0]!==170)throw Error('Replacement start did not establish a new owner');
      return {releases:log.releases,owners:guard.summary().owners,orders};
    });
    assert.equal(stress.releases,1001);assert.equal(stress.owners,0);
    assert.ok(stress.orders.ownerFirst>0);assert.ok(stress.orders.secondaryFirst>0);
    await stressPage.close();
    const page=await setup(id,true);
    const cdp=await page.context().newCDPSession(page);
    const send=(type,points)=>cdp.send('Input.dispatchTouchEvent',{type,touchPoints:points.map(([id,x])=>({id,x,y:100}))});
    await send('touchStart',[[10,100]]);
    assert.equal(await page.evaluate(()=>guard.undo()),false);
    await send('touchMove',[[10,140]]);
    await send('touchEnd',[]);
    let state=await page.evaluate(()=>snapshot());
    assert.ok(state.drags>0);assert.equal(state.releases,1);assert.equal(state.held,false);
    assert.equal(state.guard.adapted,0, 'Ordinary input must use original event unchanged');
    await send('touchStart',[[10,100]]);
    await send('touchStart',[[10,100],[20,250]]);
    assert.ok((await page.evaluate(()=>snapshot())).raw.some(e=>e.trusted&&e.ids.length===2));
    await send('touchEnd',[[20,250]]);
    assert.equal((await page.evaluate(()=>snapshot())).held,true, 'Secondary release must not release owner');
    await send('touchEnd',[]);
    await page.touchscreen.tap(650,60);
    assert.equal((await page.evaluate(()=>snapshot())).outside,1);
    await send('touchStart',[[30,100]]);
    await send('touchMove',[[30,140]]);
    const releases=(await page.evaluate(()=>snapshot())).releases;
    await send('touchCancel',[]);
    state=await page.evaluate(()=>snapshot());
    assert.equal(state.held,false, 'Cancellation must clear engine ownership');
    assert.equal(state.toolMoving,true, 'Tool-specific transient cleanup remains explicitly unresolved');
    assert.equal(state.releases,releases, 'Patch must not turn cancel into release');
    const boundaries=await page.evaluate(()=>{
      // Unknown IDs are never presumed phantom; untrusted events are untouched.
      model('touchstart',[[39,100]],[[39,100]]);
      model('touchend',[],[[39,100]]);
      log.drags=0;
      model('touchstart',[[40,100],[999,200]],[[40,100]]);
      model('touchmove',[[40,140],[999,200]],[[40,140]]);
      const unknown=log.drags;
      model('touchend',[],[[40,140]]);
      const before=guard.summary().adapted;
      model('touchstart',[[50,100],[40,140]],[[50,100]],false);
      const after=guard.summary().adapted;
      // Bounded history evicts evidence rather than guessing additional stale IDs.
      for(let id=1000;id<1300;id++){
        modelObservers.touchstart({type:'touchstart',isTrusted:true,touches:[],targetTouches:[],changedTouches:[{identifier:id}]});
        modelObservers.touchend({type:'touchend',isTrusted:true,touches:[],targetTouches:[],changedTouches:[{identifier:id}]});
      }
      return {unknown,before,after,summary:guard.summary()};
    });
    assert.equal(boundaries.unknown,0);
    assert.equal(boundaries.before,boundaries.after);
    assert.equal(boundaries.summary.retired,256);
    assert.ok(boundaries.summary.evicted>0);
    assert.equal(await page.evaluate(()=>guard.undo()),true);
    assert.equal(await page.evaluate(()=>guard.summary().enabled),false);
    await page.reload();
    assert.equal(await page.evaluate(()=>guard.summary().active),0);
    assert.equal(await page.evaluate(()=>guard.summary().enabled),true);
    assert.equal(await page.evaluate(()=>guard.summary().engines[54145]),'awaiting-module');
    await page.close();
    console.log(`PASS ${id}: original phantom refusal; patched modeled stale contacts/late end/ID reuse; 1,000 seeded owner sequences, trusted drag, genuine multitouch, outside click, reload; noncommitting engine cancellation and per-tool limitation; idle undo`);
  }
  const unsupported=await setup(54145,true,{mutated:true});
  assert.equal((await unsupported.evaluate(()=>guard.summary())).engines[54145],'unsupported-signature');
  await unsupported.close();
  const changedDeployment=await setup(54145,true,{deployment:'v12.120.0'});
  assert.equal((await changedDeployment.evaluate(()=>guard.summary())).engines[54145],'unsupported-deployment');
  const fallbackCdp=await changedDeployment.context().newCDPSession(changedDeployment);
  await fallbackCdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{id:10,x:100,y:100}]});
  await fallbackCdp.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{id:10,x:140,y:100}]});
  await fallbackCdp.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});
  const fallback=await changedDeployment.evaluate(()=>snapshot());
  assert.ok(fallback.drags>0);assert.equal(fallback.releases,1);
  assert.equal(fallback.guard.adapted,0);
  await changedDeployment.close();
  const mixed=await setup(55417,true,{vendorDeployment:'v12.120.0'});
  assert.equal((await mixed.evaluate(()=>guard.summary())).engines[55417],'unsupported-deployment');
  await mixed.close();
  const changedMouse=await setup(54145,true);
  const mouseFallback=await changedMouse.evaluate(()=>{
    const original=engine.banvasMouse.onTouchStart;
    engine.banvasMouse.onTouchStart=function(...args){return original.apply(this,args)};
    model('touchstart',[[10,100]],[[10,100]]);
    model('touchmove',[[10,140]],[[10,140]]);
    model('touchend',[],[[10,140]]);
    return snapshot();
  });
  assert.equal(mouseFallback.guard.engines[54145],'unsupported-mouse-signature');
  assert.ok(mouseFallback.drags>0);assert.equal(mouseFallback.releases,1);
  await changedMouse.close();
  for (const id of [54145,55417]) {
    const replacedEngine=await setup(id,true);
    assert.equal(await replacedEngine.evaluate(patch=>(0,eval)(patch)()===guard,patch),true,
      'Repeated installation must preserve the current document state');
    const replacementFallback=await replacedEngine.evaluate(()=>{
      const original=engine.getXYFromEvent;
      engine.getXYFromEvent=function(...args){return original.apply(this,args)};
      model('touchstart',[[10,100]],[[10,100]]);
      model('touchmove',[[10,140]],[[10,140]]);
      model('touchend',[],[[10,140]]);
      return snapshot();
    });
    assert.equal(replacementFallback.guard.engines[id],'unsupported-engine-replacement');
    assert.ok(replacementFallback.drags>0);assert.equal(replacementFallback.releases,1);
    assert.equal(replacementFallback.guard.adapted,0);
    await replacedEngine.close();
  }
  const late=await setup(54145,false);
  assert.equal(await late.evaluate(patch=>(0,eval)(patch)().status,patch),'too-late');
  await late.close();
  console.log('PASS: changed signatures, unknown/mixed deployments decline safely; unpatched trusted drag works; late installation declines; both bundle load orders exercised.');
} finally {await browser.close();}
