(function installBeastTouchRecovery(engine, staleIds) {
  const key = '__beastTouchRecovery20261019';
  if (window[key]) throw new Error('Touch recovery is already installed');
  if (!engine || typeof engine.onMouseEvent !== 'function') throw new Error('Puzzle input handler unavailable');
  const original = engine.onMouseEvent;
  const stale = new Set(staleIds);
  const state = { timeOrigin: performance.timeOrigin, filteredEvents: 0, last: null };
  const observeStart = event => {
    if (!event.isTrusted) return;
    for (const touch of event.changedTouches) stale.delete(touch.identifier);
  };
  document.addEventListener('touchstart', observeStart, { capture: true, passive: true });
  const list = touches => {
    const result = Array.from(touches).filter(touch => !stale.has(touch.identifier));
    result.item = index => result[index] || null;
    return result;
  };
  const wrapped = function(event, handlerName) {
    if (!event.isTrusted || !event.type.startsWith('touch')) return original.call(this, event, handlerName);
    const touches = list(event.touches);
    if (touches.length === event.touches.length) return original.call(this, event, handlerName);
    state.filteredEvents++;
    state.last = { type: event.type, raw: Array.from(event.touches, t => t.identifier), filtered: touches.map(t => t.identifier) };
    // Adapt only the puzzle engine's view of the original event. Native event
    // methods retain their real receiver; document observers still see raw input.
    const values = { touches, targetTouches: list(event.targetTouches), changedTouches: list(event.changedTouches) };
    const facade = new Proxy(event, {
      get(target, property) {
        if (Object.prototype.hasOwnProperty.call(values, property)) return values[property];
        const value = Reflect.get(target, property, target);
        return typeof value === 'function' ? value.bind(target) : value;
      }
    });
    return original.call(this, facade, handlerName);
  };
  engine.onMouseEvent = wrapped;
  state.summary = () => ({ timeOrigin: state.timeOrigin, currentTimeOrigin: performance.timeOrigin,
    excludedIds: Array.from(stale), filteredEvents: state.filteredEvents, last: state.last });
  state.undo = () => {
    if (engine.onMouseEvent !== wrapped) throw new Error('Puzzle input handler changed; refusing to overwrite it');
    engine.onMouseEvent = original;
    document.removeEventListener('touchstart', observeStart, true);
    delete window[key];
    return true;
  };
  window[key] = state;
  return state;
})
