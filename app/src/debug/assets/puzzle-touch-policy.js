/* Experimental prevention candidate. Not installed in the production lesson. */
(function installPuzzleTouchPolicy({ selector = 'canvas[data-beast-gesture-surface]' } = {}) {
  if (window.__beastPuzzleTouchPolicy) return window.__beastPuzzleTouchPolicy;
  const attached = new Map();
  let claimed = 0;
  function claim(event) {
    // Retain the browser's genuine event, identifiers, targets, and complete touch lists.
    if (event.cancelable) { event.preventDefault(); claimed++; }
  }
  function attach(element) {
    if (attached.has(element)) return;
    attached.set(element, [element.style.getPropertyValue('touch-action'), element.style.getPropertyPriority('touch-action')]);
    element.style.setProperty('touch-action', 'none', 'important');
    element.addEventListener('touchstart', claim, { capture: true, passive: false });
  }
  function scan(node) {
    if (node.nodeType !== Node.ELEMENT_NODE) return;
    if (node.matches(selector)) attach(node);
    node.querySelectorAll(selector).forEach(attach);
  }
  function detach(element, original) {
    element.removeEventListener('touchstart', claim, true);
    if (original[0]) element.style.setProperty('touch-action', original[0], original[1]);
    else element.style.removeProperty('touch-action');
    attached.delete(element);
  }
  const observer = new MutationObserver(records => {
    for (const record of records) record.addedNodes.forEach(scan);
    // Moving a surface within the document must not remove its input policy.
    for (const [element, original] of attached) if (!element.isConnected) detach(element, original);
  });
  observer.observe(document, { childList: true, subtree: true });
  if (document.documentElement) scan(document.documentElement);
  const policy = {
    summary: () => ({ surfaces: attached.size, claimed }),
    undo() {
      observer.disconnect();
      for (const [element, original] of attached) detach(element, original);
      delete window.__beastPuzzleTouchPolicy;
    }
  };
  window.__beastPuzzleTouchPolicy = policy;
  return policy;
})
