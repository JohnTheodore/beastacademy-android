package com.example.beast_academy_apk

/** Browser-side adapters, installed once for each document. */
internal object WebViewScripts {
    val pauseAudio = """
    (function() {
      function pauseWindow(target) {
        try {
          if (target.speechSynthesis) target.speechSynthesis.cancel();
        } catch(e) {}
        try {
          target.document.querySelectorAll('audio, video').forEach(function(el) {
            el.pause();
          });
        } catch(e) {}
        try {
          if (target.Howler) {
            target.Howler.pause();
            if (target.Howler.ctx && target.Howler.ctx.state === 'running') {
              target.Howler.ctx.suspend();
            }
          }
        } catch(e) {}
        try {
          for (var i = 0; i < target.frames.length; i++) {
            pauseWindow(target.frames[i]);
          }
        } catch(e) {}
      }
      pauseWindow(window);
      return true;
    })();
""".trimIndent()

    val audioLifecycle = """
    (function() {
      if (window.__baAudioLifecycleInstalled) return;
      window.__baAudioLifecycleInstalled = true;

      function pauseForBackground() {
        if (!document.hidden) return;
        try {
          document.querySelectorAll('audio, video').forEach(function(el) {
            el.pause();
          });
        } catch(e) {}
        try {
          if (window.Howler) {
            window.Howler.pause();
            if (window.Howler.ctx && window.Howler.ctx.state === 'running') {
              window.Howler.ctx.suspend();
            }
          }
        } catch(e) {}
      }

      document.addEventListener('visibilitychange', pauseForBackground, true);
      window.addEventListener('pagehide', pauseForBackground, true);
    })();
""".trimIndent()

    val speechPolyfill = """
        (function() {
          var _pending = {};
          var _seq = 0;

          function waitForBridge(callback, retries) {
            if (window.AndroidSpeech && window.AndroidSpeech.isReady()) {
              callback(true);
            } else if (retries <= 0) {
              callback(false);
            } else {
              setTimeout(function() { waitForBridge(callback, retries - 1); }, 300);
            }
          }

          window.__androidTtsStart = function(id) {
            var u = _pending[id];
            if (u && u.onstart) { try { u.onstart({}); } catch(e) {} }
          };
          window.__androidTtsDone = function(id) {
            var u = _pending[id];
            delete _pending[id];
            window.speechSynthesis.speaking = Object.keys(_pending).length > 0;
            if (u && u.onend) { try { u.onend({}); } catch(e) {} }
          };
          window.__androidTtsError = function(id) {
            var u = _pending[id];
            delete _pending[id];
            window.speechSynthesis.speaking = Object.keys(_pending).length > 0;
            if (u && u.onerror) { try { u.onerror({ error: 'synthesis-failed' }); } catch(e) {} }
          };

          window.SpeechSynthesisUtterance = function(text) {
            this.text = text || '';
            this.lang = 'en-US';
            this.rate = 1; this.pitch = 1; this.volume = 1;
            this.onend = null; this.onstart = null; this.onerror = null;
          };

          var _listeners = {};

          window.speechSynthesis = {
            speaking: false,
            pending: false,
            paused: false,

            addEventListener: function(type, fn) {
              if (!_listeners[type]) _listeners[type] = [];
              _listeners[type].push(fn);
            },
            removeEventListener: function(type, fn) {
              if (!_listeners[type]) return;
              _listeners[type] = _listeners[type].filter(function(f) { return f !== fn; });
            },
            dispatchEvent: function(event) {
              var fns = _listeners[event.type] || [];
              fns.forEach(function(fn) { try { fn(event); } catch(e) {} });
              return true;
            },

            getVoices: function() {
              return [{ name: 'Android TTS', lang: 'en-US', default: true, localService: true, voiceURI: 'Android TTS' }];
            },
            speak: function(utterance) {
              var self = this;
              var id = 'utt_' + (++_seq);
              _pending[id] = utterance;
              waitForBridge(function(ok) {
                if (!ok) {
                  delete _pending[id];
                  self.speaking = Object.keys(_pending).length > 0;
                  if (utterance.onerror) { try { utterance.onerror({ error: 'synthesis-unavailable' }); } catch(e) {} }
                  return;
                }
                var queued = window.AndroidSpeech.speak(id, utterance.text || '');
                if (queued) {
                  self.speaking = true;
                } else {
                  delete _pending[id];
                  self.speaking = Object.keys(_pending).length > 0;
                  if (utterance.onerror) { try { utterance.onerror({ error: 'synthesis-unavailable' }); } catch(e) {} }
                }
              }, 20);
            },
            cancel: function() {
              _pending = {};
              this.speaking = false;
              if (window.AndroidSpeech) window.AndroidSpeech.cancel();
            },
            pause: function() {},
            resume: function() {}
          };

          setTimeout(function() {
            var event = new Event('voiceschanged');
            window.speechSynthesis.dispatchEvent(event);
          }, 500);
        })();
    """.trimIndent()

}
