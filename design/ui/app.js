/* ==========================================================================
   法泽 FAZE · 设计稿演示交互
   ==========================================================================
   职责：只负责「看起来像真 App 的操作手感」，不做任何业务逻辑
   用在：design/ui/ 下所有屏（每屏末尾 <script src="app.js">）

   提供三件事：
     1. 横向拖拽滚动 —— 给容器加 data-drag-scroll（律师分类 chips 用）
     2. 密码可见切换 —— 给按钮加 data-pw-toggle（登录页眼睛用）
     3. 点击涟漪 —— 自动作用于 .btn-primary / .btn-pill-sm / .tabbar__item

   注意：这是演示稿，不是产品代码。真实项目里这些由 RN 组件承担。
   ========================================================================== */

(function () {
  "use strict";

  /* ==========================================================================
     分享面板：按需注入
     ==========================================================================
     面板结构固定（遮罩 + 底部弹层 + 4 个渠道 + 取消）。第 3 批时它是写死在
     13-article.html 里的。到第 4 批，律师名片、案例详情、海报都要用同一个面板，
     每屏复制 40 行 HTML 必然分叉——改一个渠道要改 5 个文件。

     所以改成：页面只要有 [data-share-open] 按钮、且没有静态面板，就自动注入一份。
     样式仍在 components.css（.mask / .sheet / .share-grid），这里只注入结构。
     ========================================================================== */
  var SHARE_SHEET_HTML = [
    '<div class="mask" data-share-mask hidden></div>',
    '<div class="sheet" data-share-sheet hidden role="dialog" aria-modal="true" aria-label="分享">',
    '  <div class="sheet__handle"></div>',
    '  <div class="sheet__head">',
    '    <h2 class="sheet__title">分享到</h2>',
    '    <button class="sheet__close" type="button" data-share-close aria-label="关闭分享面板">',
    '      <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">',
    '        <path d="m6.4 6.4 11.2 11.2M17.6 6.4 6.4 17.6" stroke="currentColor"',
    '              stroke-width="2" stroke-linecap="round"/>',
    '      </svg>',
    '    </button>',
    '  </div>',
    '  <div class="share-grid">',
    '    <button class="share-grid__item share-grid__item--wechat" type="button" data-share-to="微信">',
    '      <span class="share-grid__icon">',
    '        <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">',
    '          <path d="M9.4 4.2c-3.7 0-6.7 2.4-6.7 5.4 0 1.7 1 3.2 2.5 4.2l-.7 2.2 2.4-1.2c.8.2 1.6.4 2.5.4"',
    '                stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/>',
    '          <path d="M15.4 9.2c-3.1 0-5.6 2-5.6 4.5s2.5 4.5 5.6 4.5c.7 0 1.4-.1 2-.3l2 1-.6-1.8c1.2-.8 2-2 2-3.4 0-2.5-2.5-4.5-5.4-4.5Z"',
    '                stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/>',
    '        </svg>',
    '      </span>',
    '      <span class="share-grid__label">微信</span>',
    '    </button>',
    '    <button class="share-grid__item share-grid__item--moments" type="button" data-share-to="朋友圈">',
    '      <span class="share-grid__icon">',
    '        <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">',
    '          <circle cx="12" cy="12" r="8.6" stroke="currentColor" stroke-width="1.8"/>',
    '          <circle cx="12" cy="12" r="3.4" stroke="currentColor" stroke-width="1.8"/>',
    '          <circle cx="18.4" cy="5.6" r="1.6" fill="currentColor"/>',
    '        </svg>',
    '      </span>',
    '      <span class="share-grid__label">朋友圈</span>',
    '    </button>',
    '    <button class="share-grid__item share-grid__item--link" type="button" data-share-to="链接">',
    '      <span class="share-grid__icon">',
    '        <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">',
    '          <path d="M10.2 13.8a3.6 3.6 0 0 0 5.1 0l2.6-2.6a3.6 3.6 0 0 0-5.1-5.1l-1.3 1.3"',
    '                stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/>',
    '          <path d="M13.8 10.2a3.6 3.6 0 0 0-5.1 0l-2.6 2.6a3.6 3.6 0 0 0 5.1 5.1l1.3-1.3"',
    '                stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/>',
    '        </svg>',
    '      </span>',
    '      <span class="share-grid__label">复制链接</span>',
    '    </button>',
    '    <button class="share-grid__item" type="button" data-share-to="海报">',
    '      <span class="share-grid__icon">',
    '        <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">',
    '          <rect x="3.6" y="4.6" width="16.8" height="14.8" rx="2" stroke="currentColor" stroke-width="1.8"/>',
    '          <circle cx="8.8" cy="9.6" r="1.5" stroke="currentColor" stroke-width="1.6"/>',
    '          <path d="m4.4 17 4.6-4.4 3.2 3 3-2.8 4.4 4.2" stroke="currentColor" stroke-width="1.8"',
    '                stroke-linecap="round" stroke-linejoin="round"/>',
    '        </svg>',
    '      </span>',
    '      <span class="share-grid__label">生成海报</span>',
    '    </button>',
    '  </div>',
    '  <button class="sheet__cancel" type="button" data-share-close>取消</button>',
    '</div>'
  ].join("\n");

  function ensureShareSheet() {
    if (document.querySelector("[data-share-sheet]")) return;
    if (!document.querySelector("[data-share-open]")) return;
    var box = document.createElement("div");
    box.innerHTML = SHARE_SHEET_HTML;
    while (box.firstChild) {
      document.body.appendChild(box.firstChild);
    }
  }

  ensureShareSheet();

  /* ==========================================================================
     已完成的屏。点到这里没有的链接时不跳转，改为提示，
     避免演示时点出一个白屏。每做完一批，往这个数组里加。
     ========================================================================== */
  var DONE_SCREENS = [
    "01-home.html",
    "02-lawyer.html",
    "03-agent.html",
    "04-case.html",
    "05-profile.html",
    "06-login.html",
    "07-splash.html",
    "07b-privacy-prompt.html",
    "07c-language-picker.html",
    "08-register.html",
    "09-forgot.html",
    "10-agreement.html",
    "11-privacy.html",
    "12-articles.html",
    "13-article.html",
    "14-lawyer-detail.html",
    "15-lawyer-join.html",
    "16-join-status.html",
    "17-chat.html",
    "18-ai-history.html"
  ];

  /* ---------- 轻提示 ---------- */
  var toastTimer = null;

  function showToast(text) {
    var old = document.querySelector(".demo-toast");
    if (old) old.parentNode.removeChild(old);

    var box = document.createElement("div");
    box.className = "demo-toast";
    box.textContent = text;
    document.body.appendChild(box);

    window.clearTimeout(toastTimer);
    toastTimer = window.setTimeout(function () {
      if (box.parentNode) box.parentNode.removeChild(box);
    }, 1800);
  }

  /* ---------- 拦截未完成页面的跳转 ---------- */
  document.addEventListener(
    "click",
    function (e) {
      var a = e.target.closest ? e.target.closest("a[href]") : null;
      if (!a) return;

      var href = a.getAttribute("href");
      if (!href || href.charAt(0) === "#") return;
      if (href.indexOf("http") === 0) return;

      var target = href.split("?")[0].split("#")[0];
      if (DONE_SCREENS.indexOf(target) !== -1) return;

      e.preventDefault();
      e.stopPropagation();
      showToast("这一屏还没做，正在按模块往后排");
    },
    true
  );

  /* ---------- 1. 横向拖拽滚动 ---------- */
  var dragEls = document.querySelectorAll("[data-drag-scroll]");

  Array.prototype.forEach.call(dragEls, function (el) {
    var down = false;
    var moved = false;
    var startX = 0;
    var startLeft = 0;

    el.addEventListener("pointerdown", function (e) {
      down = true;
      moved = false;
      startX = e.clientX;
      startLeft = el.scrollLeft;
      try {
        el.setPointerCapture(e.pointerId);
      } catch (err) {
        /* 某些环境不支持 pointer capture，忽略即可 */
      }
    });

    el.addEventListener("pointermove", function (e) {
      if (!down) return;
      var dx = e.clientX - startX;
      if (Math.abs(dx) > 4) moved = true;
      el.scrollLeft = startLeft - dx;
    });

    function finish(e) {
      if (!down) return;
      down = false;
      try {
        el.releasePointerCapture(e.pointerId);
      } catch (err) {
        /* ignore */
      }
      /* 拖动结束后吞掉紧接着的一次 click，避免松手时误触 */
      if (moved) {
        var swallow = function (ev) {
          ev.preventDefault();
          ev.stopPropagation();
        };
        el.addEventListener("click", swallow, { capture: true, once: true });
        window.setTimeout(function () {
          el.removeEventListener("click", swallow, { capture: true });
        }, 400);
      }
    }

    el.addEventListener("pointerup", finish);
    el.addEventListener("pointercancel", finish);
    el.addEventListener("pointerleave", function (e) {
      if (down && e.buttons === 0) finish(e);
    });
  });

  /* ---------- 2. 密码可见切换 ---------- */
  var pwToggles = document.querySelectorAll("[data-pw-toggle]");

  Array.prototype.forEach.call(pwToggles, function (btn) {
    btn.addEventListener("click", function (e) {
      e.preventDefault();
      var field = btn.closest(".field");
      var input = field ? field.querySelector("input") : null;
      if (!input) return;

      var showing = input.type === "password";
      input.type = showing ? "text" : "password";
      btn.setAttribute("aria-label", showing ? "隐藏密码" : "显示密码");
      if (showing) {
        btn.classList.add("is-on");
      } else {
        btn.classList.remove("is-on");
      }
      input.focus();
    });
  });

  /* ---------- 3. 密码强度提示（注册页） ---------- */
  var strengthWrap = document.querySelector("[data-pw-strength]");
  var pwInput = document.querySelector("[data-pw-input]");

  if (strengthWrap && pwInput) {
    var strengthText = strengthWrap.querySelector("[data-pw-text]");

    var renderStrength = function (value) {
      if (!value) {
        strengthWrap.setAttribute("hidden", "");
        return;
      }
      strengthWrap.removeAttribute("hidden");

      var level = "weak";
      var label = "密码强度：弱";

      if (value.length >= 8) {
        var hasLetter = /[a-zA-Z]/.test(value);
        var hasDigit = /\d/.test(value);
        var hasSymbol = /[^a-zA-Z0-9]/.test(value);

        if (hasLetter && hasDigit && (hasSymbol || value.length >= 12)) {
          level = "strong";
          label = "密码强度：强";
        } else {
          level = "medium";
          label = "密码强度：中";
        }
      }

      strengthWrap.classList.remove(
        "pw-strength--weak",
        "pw-strength--medium",
        "pw-strength--strong"
      );
      strengthWrap.classList.add("pw-strength--" + level);
      if (strengthText) strengthText.textContent = label;
    };

    pwInput.addEventListener("input", function () {
      renderStrength(pwInput.value);
    });
  }

  /* ---------- 4. 协议勾选控制注册按钮（注册页） ---------- */
  var agreeBox = document.querySelector("[data-agree]");
  var registerBtn = document.querySelector("[data-register-btn]");

  if (agreeBox && registerBtn) {
    agreeBox.addEventListener("change", function () {
      if (agreeBox.checked) {
        registerBtn.classList.remove("is-disabled");
        registerBtn.removeAttribute("aria-disabled");
      } else {
        registerBtn.classList.add("is-disabled");
        registerBtn.setAttribute("aria-disabled", "true");
      }
    });
  }

  /* ---------- 5. 登录方式 Tab 切换（登录页） ---------- */
  var loginTabs = document.querySelectorAll("[data-login-tab]");
  var loginPanes = document.querySelectorAll("[data-pane]");

  if (loginTabs.length && loginPanes.length) {
    Array.prototype.forEach.call(loginTabs, function (tab) {
      tab.addEventListener("click", function () {
        var mode = tab.getAttribute("data-login-tab");

        Array.prototype.forEach.call(loginTabs, function (item) {
          if (item === tab) {
            item.classList.add("is-active");
          } else {
            item.classList.remove("is-active");
          }
        });

        Array.prototype.forEach.call(loginPanes, function (pane) {
          if (pane.getAttribute("data-pane") === mode) {
            pane.removeAttribute("hidden");
          } else {
            pane.setAttribute("hidden", "");
          }
        });
      });
    });
  }

  /* ---------- 6. 首启隐私弹窗（启动页状态） ---------- */
  var agreeSplash = document.querySelector("[data-agree-splash]");
  var declineSplash = document.querySelector("[data-decline-splash]");

  if (agreeSplash) {
    agreeSplash.addEventListener("click", function () {
      window.location.href = "01-home.html";
    });
  }

  if (declineSplash) {
    declineSplash.addEventListener("click", function () {
      showToast("需要同意隐私政策后才能继续使用");
    });
  }

  /* ---------- 7. 首启语言选择（启动页状态） ---------- */
  var langList = document.querySelector("[data-lang-list]");

  if (langList) {
    var langItems = langList.querySelectorAll("[data-lang]");

    Array.prototype.forEach.call(langItems, function (item) {
      item.addEventListener("click", function () {
        Array.prototype.forEach.call(langItems, function (other) {
          if (other === item) {
            other.classList.add("is-active");
          } else {
            other.classList.remove("is-active");
          }
        });
      });
    });
  }

  /* ---------- 8. 点击涟漪 ---------- */
  var rippleTargets = ".btn-primary, .btn-pill-sm, .login-btn, .tabbar__item, .lawyer-card__cta";

  document.addEventListener(
    "pointerdown",
    function (e) {
      var target = e.target.closest(rippleTargets);
      if (!target) return;

      var rect = target.getBoundingClientRect();
      var size = Math.max(rect.width, rect.height) * 1.6;
      var dot = document.createElement("span");
      dot.className = "tap-ripple";
      dot.style.width = size + "px";
      dot.style.height = size + "px";
      dot.style.left = e.clientX - rect.left - size / 2 + "px";
      dot.style.top = e.clientY - rect.top - size / 2 + "px";
      target.appendChild(dot);
      window.setTimeout(function () {
        if (dot.parentNode) dot.parentNode.removeChild(dot);
      }, 520);
    },
    { passive: true }
  );

  /* ==========================================================================
     9. 四态切换（第 3 批起）
     ==========================================================================
     每个列表 / 详情屏用 <section data-state="normal|loading|empty|error"> 标出四态，
     默认显示 normal。用 URL hash 切换：#state-loading / #state-empty / #state-error。
     _12-articles-states.html 就是靠 iframe 带 hash 并排展示四态的。
     ========================================================================== */
  var stateNodes = document.querySelectorAll("[data-state]");

  function applyState(name) {
    /* 状态名不写死四个：入驻审核页有 normal / rejected / passed 三种，
       各屏按自己的业务定义 data-state，这里只要保证「启用的只有一个」 */
    var known = [];
    Array.prototype.forEach.call(stateNodes, function (node) {
      var value = node.getAttribute("data-state");
      if (value && known.indexOf(value) === -1) known.push(value);
    });
    if (known.indexOf(name) === -1) return;

    Array.prototype.forEach.call(stateNodes, function (node) {
      if (node.getAttribute("data-state") === name) {
        node.removeAttribute("hidden");
      } else {
        node.setAttribute("hidden", "");
      }
    });
  }

  if (stateNodes.length) {
    var hash = window.location.hash.replace(/^#/, "");
    if (hash.indexOf("state-") === 0) {
      applyState(hash.slice(6));
    }
  }

  /* ==========================================================================
     10. 下拉刷新（演示）
     ==========================================================================
     真实 App 是手势下拉触发。设计稿没法接真实手势，改成进页面自动播放一次，
     让人看到「刷新中 → 收起」的手感。1.3 秒内结束，不影响走查截图。
     ========================================================================== */
  var refresher = document.querySelector("[data-refresher]");
  var refresherText = document.querySelector("[data-refresher-text]");

  if (refresher) {
    window.setTimeout(function () {
      refresher.classList.add("is-spinning");
      var screen = refresher.parentNode;
      if (screen) screen.classList.add("is-pulling");
      if (refresherText) refresherText.textContent = "正在刷新…";

      window.setTimeout(function () {
        refresher.classList.remove("is-spinning");
        if (screen) screen.classList.remove("is-pulling");
        if (refresherText) refresherText.textContent = "下拉刷新";
      }, 900);
    }, 400);
  }

  /* ==========================================================================
     11. 上拉加载更多（演示）
     ==========================================================================
     点一次 → 转圈 → 变成「没有更多了」。真实实现是滚动到底自动触发分页。
     ========================================================================== */
  var loadmore = document.querySelector("[data-loadmore]");

  if (loadmore) {
    var loadmoreText = loadmore.querySelector("[data-loadmore-text]");

    loadmore.addEventListener("click", function () {
      if (loadmore.classList.contains("loadmore--end")) return;
      if (loadmore.classList.contains("is-spinning")) return;

      loadmore.classList.add("is-spinning");
      if (loadmoreText) loadmoreText.textContent = "正在加载…";

      window.setTimeout(function () {
        loadmore.classList.remove("is-spinning");
        loadmore.classList.add("loadmore--end");
        if (loadmoreText) loadmoreText.textContent = "没有更多了";
      }, 800);
    });
  }

  /* ==========================================================================
     12. 收藏文章
     ========================================================================== */
  var favBtn = document.querySelector("[data-fav]");

  if (favBtn) {
    /* 文章是「收藏」、律师是「关注」，两屏文案不同，
       所以由页面用 data-fav-on / data-fav-off 指定 Toast，用 data-fav-text 指定按钮文字 */
    var favTextEl = favBtn.querySelector("[data-fav-text]");
    var favOnText = favBtn.getAttribute("data-fav-on") || "已收藏，可在「我的 - 我的收藏」查看";
    var favOffText = favBtn.getAttribute("data-fav-off") || "已取消收藏";

    favBtn.addEventListener("click", function () {
      var on = favBtn.classList.toggle("is-on");
      favBtn.setAttribute("aria-pressed", on ? "true" : "false");
      favBtn.setAttribute("aria-label", on ? "取消关注" : "关注");

      if (favTextEl) {
        var textOn = favBtn.getAttribute("data-fav-text-on") || "已关注";
        var textOff = favBtn.getAttribute("data-fav-text-off") || "关注";
        favTextEl.textContent = on ? textOn : textOff;
      }

      showToast(on ? favOnText : favOffText);
    });
  }

  /* ==========================================================================
     13. 分享面板
     ==========================================================================
     结构在 13-article.html 里：.mask + .sheet(.share-grid)。app.js 只切 hidden。
     ========================================================================== */
  var shareOpen = document.querySelectorAll("[data-share-open]");
  var shareClose = document.querySelectorAll("[data-share-close]");
  var shareMask = document.querySelector("[data-share-mask]");
  var shareSheet = document.querySelector("[data-share-sheet]");

  function setShare(open) {
    if (shareMask) {
      if (open) shareMask.removeAttribute("hidden");
      else shareMask.setAttribute("hidden", "");
    }
    if (shareSheet) {
      if (open) shareSheet.removeAttribute("hidden");
      else shareSheet.setAttribute("hidden", "");
    }
  }

  if (shareSheet) {
    Array.prototype.forEach.call(shareOpen, function (btn) {
      btn.addEventListener("click", function () {
        setShare(true);
      });
    });

    Array.prototype.forEach.call(shareClose, function (btn) {
      btn.addEventListener("click", function () {
        setShare(false);
      });
    });

    if (shareMask) {
      shareMask.addEventListener("click", function () {
        setShare(false);
      });
    }

    /* 点渠道：演示文案跟着渠道走，不真的调起微信 */
    var shareTo = shareSheet.querySelectorAll("[data-share-to]");
    Array.prototype.forEach.call(shareTo, function (btn) {
      btn.addEventListener("click", function () {
        var to = btn.getAttribute("data-share-to");
        setShare(false);
        if (to === "链接") {
          showToast("链接已复制");
        } else if (to === "海报") {
          var loadingMask = document.querySelector("[data-loading]");
          if (loadingMask) {
            loadingMask.removeAttribute("hidden");
            window.setTimeout(function () {
              loadingMask.setAttribute("hidden", "");
              showToast("海报已保存到相册");
            }, 1300);
          } else {
            showToast("正在生成海报…");
          }
        } else {
          showToast("演示环境无法调起" + to);
        }
      });
    });

    document.addEventListener("keydown", function (e) {
      if (e.key === "Escape") setShare(false);
    });
  }

  /* ==========================================================================
     14. 分类筛选（律师列表 / 文章列表共用）
     ==========================================================================
     切 active + 短暂回到加载态，让人看到骨架屏。真实实现是重新请求列表。
     ========================================================================== */
  var chips = document.querySelectorAll(".filter__chip");

  if (chips.length && stateNodes.length) {
    Array.prototype.forEach.call(chips, function (chip) {
      /* 有专属筛选逻辑的 chips 跳过通用流程：
         25 的模板切换（data-tpl）走第 26 段，不该闪骨架屏 */
      if (chip.hasAttribute("data-tpl")) return;

      chip.addEventListener("click", function (e) {
        e.preventDefault();

        Array.prototype.forEach.call(chips, function (other) {
          if (other === chip) other.classList.add("filter__chip--active");
          else other.classList.remove("filter__chip--active");
        });

        var label = (chip.textContent || "").trim();
        applyState("loading");

        window.setTimeout(function () {
          applyState("normal");
          showToast("已筛选：" + label);
        }, 600);
      });
    });
  }

  /* ==========================================================================
     15. 重试（错误态里的按钮）
     ========================================================================== */
  var retryButtons = document.querySelectorAll("[data-retry]");

  Array.prototype.forEach.call(retryButtons, function (btn) {
    btn.addEventListener("click", function () {
      if (stateNodes.length) {
        applyState("loading");
        window.setTimeout(function () {
          applyState("normal");
          showToast("已重新加载");
        }, 700);
      } else {
        showToast("已重新加载");
      }
    });
  });

  /* ==========================================================================
     16. 退出登录二次确认（我的页）
     ==========================================================================
     不可撤销操作必须先问一次。确认后跳登录页，取消就关掉。
     ========================================================================== */
  var logoutOpen = document.querySelector("[data-logout-open]");
  var logoutClosers = document.querySelectorAll("[data-logout-close]");
  var logoutConfirm = document.querySelector("[data-logout-confirm]");
  var logoutMask = document.querySelector("[data-logout-mask]");
  var logoutDialog = document.querySelector("[data-logout-dialog]");

  function setLogout(open) {
    if (logoutMask) {
      if (open) logoutMask.removeAttribute("hidden");
      else logoutMask.setAttribute("hidden", "");
    }
    if (logoutDialog) {
      if (open) logoutDialog.removeAttribute("hidden");
      else logoutDialog.setAttribute("hidden", "");
    }
  }

  if (logoutOpen) {
    logoutOpen.addEventListener("click", function () {
      setLogout(true);
    });

    Array.prototype.forEach.call(logoutClosers, function (btn) {
      btn.addEventListener("click", function () {
        setLogout(false);
      });
    });

    if (logoutMask) {
      logoutMask.addEventListener("click", function () {
        setLogout(false);
      });
    }

    if (logoutConfirm) {
      logoutConfirm.addEventListener("click", function () {
        setLogout(false);
        window.location.href = "06-login.html";
      });
    }
  }

  /* ==========================================================================
     17. 走查开关：用 hash 直接打开浮层
     ==========================================================================
     浮层默认 hidden，静态截图看不到，也没法用截图工具点按钮。
     _walkthrough/overlays.html 用 iframe 带这些 hash 把四个浮层摆出来走查：
       #open-share   分享面板（13-article.html）
       #open-logout  退出登录二次确认（05-profile.html）
       #open-loading 遮罩式 Loading（13-article.html）
     不影响正常使用：只有 hash 精确匹配时才会动作。
     ========================================================================== */
  var walkHash = window.location.hash.replace(/^#/, "");

  if (walkHash === "open-share" && shareSheet) {
    setShare(true);
  }

  if (walkHash === "open-logout" && logoutOpen) {
    setLogout(true);
  }

  if (walkHash === "open-loading") {
    var walkLoading = document.querySelector("[data-loading]");
    if (walkLoading) walkLoading.removeAttribute("hidden");
  }
  /* ==========================================================================
     18. 四步表单（律师入驻页）
     ==========================================================================
     一个 HTML 装四步：<section data-step="1..4"> + 步骤条 <div data-step-dot="1..4">
     切步只改 hidden 与 is-active / is-done，不重新渲染。
     走查用 hash：#step-2 / #step-3 / #step-4 直接打开某一步。
     ========================================================================== */
  var stepPanels = document.querySelectorAll("[data-step]");
  var stepDots = document.querySelectorAll("[data-step-dot]");
  var pageScreen = document.querySelector(".screen");

  function applyStep(value) {
    if (!stepPanels.length) return;
    var n = parseInt(value, 10);
    if (!(n >= 1 && n <= stepPanels.length)) return;

    Array.prototype.forEach.call(stepPanels, function (panel) {
      if (parseInt(panel.getAttribute("data-step"), 10) === n) {
        panel.removeAttribute("hidden");
      } else {
        panel.setAttribute("hidden", "");
      }
    });

    Array.prototype.forEach.call(stepDots, function (dot) {
      var index = parseInt(dot.getAttribute("data-step-dot"), 10);
      dot.classList.remove("is-active", "is-done");
      if (index === n) dot.classList.add("is-active");
      else if (index < n) dot.classList.add("is-done");
    });
  }

  function goStep(value) {
    applyStep(value);
    if (pageScreen) pageScreen.scrollTop = 0;
  }

  var stepNextBtns = document.querySelectorAll("[data-step-next]");
  var stepPrevBtns = document.querySelectorAll("[data-step-prev]");

  Array.prototype.forEach.call(stepNextBtns, function (btn) {
    btn.addEventListener("click", function () {
      goStep(btn.getAttribute("data-step-next"));
    });
  });

  Array.prototype.forEach.call(stepPrevBtns, function (btn) {
    btn.addEventListener("click", function () {
      goStep(btn.getAttribute("data-step-prev"));
    });
  });

  if (stepPanels.length) {
    var stepHash = window.location.hash.replace(/^#/, "");
    if (stepHash.indexOf("step-") === 0) {
      applyStep(stepHash.slice(5));
    }
  }

  /* 身份单选（学生 / 律师） */
  var roleButtons = document.querySelectorAll("[data-role]");

  Array.prototype.forEach.call(roleButtons, function (role) {
    role.addEventListener("click", function () {
      Array.prototype.forEach.call(roleButtons, function (other) {
        if (other === role) other.classList.add("is-active");
        else other.classList.remove("is-active");
      });
    });
  });

  /* 专长多选 */
  var pickButtons = document.querySelectorAll("[data-pick]");

  Array.prototype.forEach.call(pickButtons, function (pick) {
    pick.addEventListener("click", function () {
      pick.classList.toggle("is-active");
    });
  });

  /* 材料上传位：演示用，点一下切换「已上传 / 待上传」 */
  var uploadButtons = document.querySelectorAll("[data-upload]");

  Array.prototype.forEach.call(uploadButtons, function (up) {
    up.addEventListener("click", function () {
      up.classList.toggle("is-done");
      showToast(up.classList.contains("is-done") ? "已选择照片（演示）" : "已移除");
    });
  });

  /* ==========================================================================
     19. IM 聊天页
     ==========================================================================
     四个交互：图片预览、消息长按菜单、附件+权限说明面板、发消息。
     全部只操作 DOM 与 class，不涉及任何真实通讯。
     ========================================================================== */
  function toggleLayer(node, open) {
    if (!node) return;
    if (open) node.removeAttribute("hidden");
    else node.setAttribute("hidden", "");
  }

  /* --- 图片预览 --- */
  var imgPreview = document.querySelector("[data-img-preview]");
  var imgMask = document.querySelector("[data-img-mask]");
  var imgOpeners = document.querySelectorAll("[data-img-open]");
  var imgClosers = document.querySelectorAll("[data-img-close]");
  var imgSave = document.querySelector("[data-img-save]");

  function setImagePreview(open) {
    toggleLayer(imgMask, open);
    toggleLayer(imgPreview, open);
  }

  if (imgPreview) {
    Array.prototype.forEach.call(imgOpeners, function (btn) {
      btn.addEventListener("click", function () {
        setImagePreview(true);
      });
    });
    Array.prototype.forEach.call(imgClosers, function (btn) {
      btn.addEventListener("click", function () {
        setImagePreview(false);
      });
    });
    if (imgMask) {
      imgMask.addEventListener("click", function () {
        setImagePreview(false);
      });
    }
    if (imgSave) {
      imgSave.addEventListener("click", function () {
        showToast("已保存到相册（演示）");
      });
    }
  }

  /* --- 消息长按菜单 --- */
  var msgMenu = document.querySelector("[data-msg-menu]");
  var menuMask = document.querySelector("[data-menu-mask]");

  function setMsgMenu(open) {
    toggleLayer(menuMask, open);
    toggleLayer(msgMenu, open);
  }

  function bindMsgBubble(bubble) {
    if (!bubble) return;
    bubble.addEventListener("click", function () {
      setMsgMenu(true);
    });
  }

  if (msgMenu) {
    Array.prototype.forEach.call(document.querySelectorAll("[data-msg]"), bindMsgBubble);

    if (menuMask) {
      menuMask.addEventListener("click", function () {
        setMsgMenu(false);
      });
    }

    var menuCopy = document.querySelector("[data-menu-copy]");
    var menuRecall = document.querySelector("[data-menu-recall]");
    var menuDelete = document.querySelector("[data-menu-delete]");

    if (menuCopy) {
      menuCopy.addEventListener("click", function () {
        setMsgMenu(false);
        showToast("已复制");
      });
    }
    if (menuRecall) {
      menuRecall.addEventListener("click", function () {
        setMsgMenu(false);
        showToast("已撤回（演示）");
      });
    }
    if (menuDelete) {
      menuDelete.addEventListener("click", function () {
        setMsgMenu(false);
        showToast("已删除（演示）");
      });
    }
  }

  /* --- 附件面板（含权限说明） --- */
  var attachSheet = document.querySelector("[data-attach-sheet]");
  var attachMask = document.querySelector("[data-attach-mask]");

  function setAttach(open) {
    toggleLayer(attachMask, open);
    toggleLayer(attachSheet, open);
  }

  if (attachSheet) {
    Array.prototype.forEach.call(document.querySelectorAll("[data-attach-open]"), function (btn) {
      btn.addEventListener("click", function () {
        setAttach(true);
      });
    });
    Array.prototype.forEach.call(document.querySelectorAll("[data-attach-close]"), function (btn) {
      btn.addEventListener("click", function () {
        setAttach(false);
      });
    });
    if (attachMask) {
      attachMask.addEventListener("click", function () {
        setAttach(false);
      });
    }

    var needCamera = document.querySelector("[data-need-camera]");
    var needGallery = document.querySelector("[data-need-gallery]");
    var attachFile = document.querySelector("[data-attach-file]");

    if (needCamera) {
      needCamera.addEventListener("click", function () {
        setAttach(false);
        showToast("演示环境不会真的申请相机权限");
      });
    }
    if (needGallery) {
      needGallery.addEventListener("click", function () {
        setAttach(false);
        showToast("演示环境不会真的申请相册权限");
      });
    }
    if (attachFile) {
      attachFile.addEventListener("click", function () {
        setAttach(false);
        showToast("文件选择器（演示）");
      });
    }
  }

  /* --- 发消息 --- */
  var chatBody = document.querySelector("[data-chat]");
  var chatInput = document.querySelector("[data-chat-input]");
  var chatSend = document.querySelector("[data-chat-send]");

  function currentTime() {
    var d = new Date();
    var h = d.getHours();
    var m = d.getMinutes();
    return (h < 10 ? "0" : "") + h + ":" + (m < 10 ? "0" : "") + m;
  }

  function sendMessage(text) {
    if (!chatBody || !text) return;

    var row = document.createElement("div");
    row.className = "msg msg--out";

    var wrap = document.createElement("div");
    wrap.className = "msg__wrap";

    var bubble = document.createElement("div");
    bubble.className = "msg__bubble";
    bubble.setAttribute("data-msg", "");
    /* 用 textContent 而不是 innerHTML：用户输入的内容不能当 HTML 解析 */
    bubble.textContent = text;

    var time = document.createElement("p");
    time.className = "msg__time";
    time.textContent = currentTime();

    wrap.appendChild(bubble);
    wrap.appendChild(time);
    row.appendChild(wrap);

    /* 插到快捷问题之前，快捷问题始终停在最后 */
    var quick = chatBody.querySelector(".quick-ask");
    if (quick) chatBody.insertBefore(row, quick);
    else chatBody.appendChild(row);

    bindMsgBubble(bubble);
    chatBody.scrollTop = chatBody.scrollHeight;
  }

  if (chatSend) {
    chatSend.addEventListener("click", function () {
      if (!chatInput) return;
      var text = chatInput.value.trim();
      if (!text) {
        showToast("说点什么再发送");
        return;
      }
      sendMessage(text);
      chatInput.value = "";
    });
  }

  if (chatInput) {
    chatInput.addEventListener("keydown", function (e) {
      if (e.key === "Enter") {
        e.preventDefault();
        if (chatSend) chatSend.click();
      }
    });
  }

  Array.prototype.forEach.call(document.querySelectorAll("[data-quick]"), function (chip) {
    chip.addEventListener("click", function () {
      sendMessage((chip.textContent || "").trim());
    });
  });

  /* 走查开关：hash 直接打开聊天页的三个浮层 */
  var chatHash = window.location.hash.replace(/^#/, "");
  if (chatHash === "open-image") setImagePreview(true);
  if (chatHash === "open-menu") setMsgMenu(true);
  if (chatHash === "open-attach") setAttach(true);

  /* ==========================================================================
     20. 清空当前对话（AI 对话页）
     ==========================================================================
     不可撤销操作先问一次，和退出登录用同一个 .dialog 组件。
     ========================================================================== */
  var clearOpen = document.querySelector("[data-clear-open]");
  var clearClosers = document.querySelectorAll("[data-clear-close]");
  var clearConfirm = document.querySelector("[data-clear-confirm]");
  var clearMask = document.querySelector("[data-clear-mask]");
  var clearDialog = document.querySelector("[data-clear-dialog]");

  function setClearDialog(open) {
    toggleLayer(clearMask, open);
    toggleLayer(clearDialog, open);
  }

  if (clearOpen) {
    clearOpen.addEventListener("click", function () {
      setClearDialog(true);
    });

    Array.prototype.forEach.call(clearClosers, function (btn) {
      btn.addEventListener("click", function () {
        setClearDialog(false);
      });
    });

    if (clearMask) {
      clearMask.addEventListener("click", function () {
        setClearDialog(false);
      });
    }

    if (clearConfirm) {
      clearConfirm.addEventListener("click", function () {
        setClearDialog(false);
        showToast("当前对话已清空");
      });
    }

    /* 走查开关必须放在这里，不能放在前面那段 walkHash 里：
       函数声明会提升，但 clearMask / clearDialog 是 var，前面调用时还没赋值。 */
    if (walkHash === "open-clear") setClearDialog(true);
  }

  /* ==========================================================================
     21. 对话历史 · 左滑删除
     ==========================================================================
     每行是可横向拖动的容器（data-drag-scroll 复用律师分类条那套拖拽），
     左滑 88px 露出红色删除块。点删除就移除这一行。
     ========================================================================== */
  Array.prototype.forEach.call(document.querySelectorAll("[data-del]"), function (btn) {
    btn.addEventListener("click", function (e) {
      e.preventDefault();
      var row = btn.closest(".hist");
      if (row && row.parentNode) row.parentNode.removeChild(row);
      showToast("已删除这条记录");
    });
  });

  /* ==========================================================================
     22. 单选组（第 6 批：在线立案的案件类型 / 纠纷类型、送达方式）
     ==========================================================================
     已有的 [data-pick] 是**多选 toggle**（律师入驻的专长领域，一个人可以擅长几样），
     不能直接拿来当单选用。这里新增两组单选：
       [data-case-type]  案件类型三选一（民事 / 行政 / 刑事）
       [data-pick-one]   纠纷类型单选
       [data-method]     送达方式三选一

     注意：三个都是"全局单选"，即整个页面里同组只有一个是 is-active。
     同一页只出现一组同名钩子，所以不用按容器分组。
     ========================================================================== */
  function bindSingleChoice(selector) {
    var options = document.querySelectorAll(selector);

    Array.prototype.forEach.call(options, function (item) {
      item.addEventListener("click", function () {
        /* 作用域取最近的 [data-choice-group]，没有就取父元素。
           第 8 批的 25-doc-other 一屏里有多组同名单选（提交形式 / 工时制度 /
           贸易术语 / 争议解决），原来的全局单选会让它们互相取消。 */
        var scope = item.closest("[data-choice-group]") || item.parentNode;
        Array.prototype.forEach.call(
          scope.querySelectorAll(selector),
          function (other) {
            if (other === item) other.classList.add("is-active");
            else other.classList.remove("is-active");
          }
        );
      });
    });
  }

  bindSingleChoice("[data-case-type]");
  bindSingleChoice("[data-pick-one]");
  bindSingleChoice("[data-method]");

  /* ==========================================================================
     23. 复制（第 6 批：立案成功页的案件编号）
     ==========================================================================
     clipboard API 要求安全上下文，http://127.0.0.1 和 localhost 算安全，
     但如果设计稿被用 file:// 打开就会失败 —— 所以失败分支要给出兜底提示，
     不能静默什么都不发生。
     ========================================================================== */
  Array.prototype.forEach.call(document.querySelectorAll("[data-copy]"), function (btn) {
    btn.addEventListener("click", function () {
      var text = btn.getAttribute("data-copy") || "";

      function fallback() {
        showToast("长按上面这串编号手动复制");
      }

      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(text).then(function () {
          showToast("已复制：" + text);
        }, fallback);
      } else {
        fallback();
      }
    });
  });

  /* ==========================================================================
     24. 文书签收二次确认（第 6 批：案件送达页）
     ==========================================================================
     签收是不可撤销动作，而且会起算诉讼期限，所以必须问一次。
     确认框的标题要带上具体文书名 —— 泛泛地说「确认签收？」人记不住是哪一份。
     ========================================================================== */
  var signMask = document.querySelector("[data-sign-mask]");
  var signDialog = document.querySelector("[data-sign-dialog]");
  var signTitle = document.querySelector("[data-sign-title]");
  var signClosers = document.querySelectorAll("[data-sign-close]");
  var signConfirm = document.querySelector("[data-sign-confirm]");

  function setSignDialog(open) {
    toggleLayer(signMask, open);
    toggleLayer(signDialog, open);
  }

  if (signDialog) {
    Array.prototype.forEach.call(
      document.querySelectorAll("[data-sign-open]"),
      function (btn) {
        btn.addEventListener("click", function () {
          var name = btn.getAttribute("data-sign-open");
          if (signTitle && name) {
            signTitle.textContent = "确认签收《" + name + "》？";
          }
          setSignDialog(true);
        });
      }
    );

    Array.prototype.forEach.call(signClosers, function (btn) {
      btn.addEventListener("click", function () {
        setSignDialog(false);
      });
    });

    if (signMask) {
      signMask.addEventListener("click", function () {
        setSignDialog(false);
      });
    }

    if (signConfirm) {
      signConfirm.addEventListener("click", function () {
        setSignDialog(false);
        showToast("已签收，送达时间已记录");
      });
    }

    /* 走查开关：hash #open-sign 直接打开签收确认框。
       必须放在变量赋值之后 —— 函数声明会提升、var 不会，
       第 5 批的 #open-clear 就是这么静默失效过一次。 */
    if (walkHash === "open-sign") {
      if (signTitle) signTitle.textContent = "确认签收《受理案件通知书》？";
      setSignDialog(true);
    }
  }

  /* ==========================================================================
     25. 文书表单（第 8 批：24-doc-lawsuit / 25-doc-other）
     ==========================================================================
     三个交互：
       data-phrase    把常用诉请表述追加到诉讼请求输入框（真追加，不是替换）
       data-add-party 添加一份原告/被告 —— 设计稿只提示，不克隆 DOM。
                      真实项目里这是列表渲染，复制节点会把 id 也复制成重复的。
       data-draft     保存草稿（提示）
     ========================================================================== */
  var claimBox = document.querySelector("[data-claim-box]");

  Array.prototype.forEach.call(
    document.querySelectorAll("[data-phrase]"),
    function (btn) {
      btn.addEventListener("click", function () {
        if (!claimBox) return;
        var text = btn.getAttribute("data-phrase") || "";
        /* 上一条请求没以分号结尾时补一个，否则两句话会粘在一起 */
        var cur = claimBox.value.replace(/\s+$/, "");
        if (cur && !/[；;。]$/.test(cur)) cur += "；";
        claimBox.value = cur + text;
        claimBox.focus();
        /* 光标放到末尾，方便直接补金额 */
        claimBox.selectionStart = claimBox.selectionEnd = claimBox.value.length;
      });
    }
  );

  Array.prototype.forEach.call(
    document.querySelectorAll("[data-add-party]"),
    function (btn) {
      btn.addEventListener("click", function () {
        var kind = btn.getAttribute("data-add-party");
        var MESSAGES = {
          plaintiff: "已添加一份原告信息",
          defendant: "已添加一份被告信息",
          evidence: "已添加一份证据条目"
        };
        showToast(MESSAGES[kind] || "已添加");
      });
    }
  );

  var draftBtn = document.querySelector("[data-draft]");
  if (draftBtn) {
    draftBtn.addEventListener("click", function () {
      showToast("草稿已保存，可在「我的文书」继续编辑");
    });
  }

  /* ==========================================================================
     26. 文书模板切换（第 8 批：25-doc-other）
     ==========================================================================
     一个页面三套字段。真实项目里这是后台 field_schema 驱动的渲染，
     设计稿用一个 HTML 装三个 data-schema 容器来模拟同一件事 ——
     不拆成三个静态页，否则加第四个模板时又要再画一屏。
     ========================================================================== */
  var tplChips = document.querySelectorAll("[data-tpl]");
  var schemaPanels = document.querySelectorAll("[data-schema]");
  var schemaTitle = document.querySelector("[data-schema-title]");
  var schemaSub = document.querySelector("[data-schema-sub]");
  var schemaScreen = document.querySelector(".screen");

  /* 标题与副标题跟着模板走，避免切到劳动合同后标题还写着「证据清单」 */
  var SCHEMA_META = {
    evidence: ["证据清单", "列明证据名称、来源与证明目的，用于随起诉状一并提交"],
    labor: ["劳动合同", "按《劳动合同法》必备条款生成，可直接打印签署"],
    ec: ["跨境电商合同", "按国际贸易惯例生成，含贸易术语与争议解决条款"]
  };

  Array.prototype.forEach.call(tplChips, function (chip) {
    chip.addEventListener("click", function () {
      var key = chip.getAttribute("data-tpl");

      Array.prototype.forEach.call(tplChips, function (other) {
        if (other === chip) other.classList.add("filter__chip--active");
        else other.classList.remove("filter__chip--active");
      });

      Array.prototype.forEach.call(schemaPanels, function (panel) {
        if (panel.getAttribute("data-schema") === key) panel.removeAttribute("hidden");
        else panel.setAttribute("hidden", "");
      });

      var meta = SCHEMA_META[key];
      if (meta) {
        if (schemaTitle) schemaTitle.textContent = meta[0];
        if (schemaSub) schemaSub.textContent = meta[1];
      }
      /* 换模板后回到顶部，否则会停在上一个模板的表单中间 */
      if (schemaScreen) schemaScreen.scrollTop = 0;
    });
  });

  /* 走查开关：hash #schema-labor / #schema-ec 直接切到某套字段
     （走查文件 _walkthrough/24-28-doc.html 用它把三套模板并排摆出来） */
  if (walkHash.indexOf("schema-") === 0) {
    var initialTpl = document.querySelector(
      '[data-tpl="' + walkHash.slice(7) + '"]'
    );
    if (initialTpl) initialTpl.click();
  }

  /* ==========================================================================
     27. 文书预览（第 8 批：26-doc-preview）
     ==========================================================================
     三个浮层/动作：
       导出现层面板    [data-export-*]   PDF / Word 二选一
       签章面板        [data-seal-*]     不可撤销，需二次说明
       保存到云端      [data-save]       提示
     ========================================================================== */
  var exportMask = document.querySelector("[data-export-mask]");
  var exportSheet = document.querySelector("[data-export-sheet]");

  function setExportSheet(open) {
    toggleLayer(exportMask, open);
    toggleLayer(exportSheet, open);
  }

  if (exportSheet) {
    var exportOpen = document.querySelector("[data-export-open]");
    if (exportOpen) {
      exportOpen.addEventListener("click", function () {
        setExportSheet(true);
      });
    }
    Array.prototype.forEach.call(
      document.querySelectorAll("[data-export-close]"),
      function (btn) {
        btn.addEventListener("click", function () {
          setExportSheet(false);
        });
      }
    );
    if (exportMask) {
      exportMask.addEventListener("click", function () {
        setExportSheet(false);
      });
    }
    Array.prototype.forEach.call(
      document.querySelectorAll("[data-export]"),
      function (btn) {
        btn.addEventListener("click", function () {
          var kind = btn.getAttribute("data-export");
          setExportSheet(false);
          showToast(kind === "pdf" ? "正在导出 PDF…" : "正在导出 Word…");
        });
      }
    );
  }

  var sealMask = document.querySelector("[data-seal-mask]");
  var sealSheet = document.querySelector("[data-seal-sheet]");

  function setSealSheet(open) {
    toggleLayer(sealMask, open);
    toggleLayer(sealSheet, open);
  }

  if (sealSheet) {
    var sealOpen = document.querySelector("[data-seal-open]");
    if (sealOpen) {
      sealOpen.addEventListener("click", function () {
        setSealSheet(true);
      });
    }
    Array.prototype.forEach.call(
      document.querySelectorAll("[data-seal-close]"),
      function (btn) {
        btn.addEventListener("click", function () {
          setSealSheet(false);
        });
      }
    );
    if (sealMask) {
      sealMask.addEventListener("click", function () {
        setSealSheet(false);
      });
    }
    var sealConfirm = document.querySelector("[data-seal-confirm]");
    if (sealConfirm) {
      sealConfirm.addEventListener("click", function () {
        setSealSheet(false);
        showToast("已盖章，本份文书已锁定");
      });
    }

    /* 走查开关：hash #open-seal 直接打开签章面板 */
    if (walkHash === "open-seal") setSealSheet(true);
  }

  var saveBtn = document.querySelector("[data-save]");
  if (saveBtn) {
    saveBtn.addEventListener("click", function () {
      showToast("已保存到「我的文书」");
    });
  }

  /* ==========================================================================
     29. 我的文书筛选（第 8 批：27-doc-mine）
     ==========================================================================
     草稿 / 已完成两组。注意「已盖章」在数据上属于「已完成」，
     所以筛选只看 data-doc-state（draft / done），不看标签文字。
     ========================================================================== */
  var docChips = document.querySelectorAll("[data-doc-filter]");
  var docItems = document.querySelectorAll(".doc-item[data-doc-state]");

  Array.prototype.forEach.call(docChips, function (chip) {
    chip.addEventListener("click", function () {
      var key = chip.getAttribute("data-doc-filter");

      Array.prototype.forEach.call(docChips, function (other) {
        if (other === chip) other.classList.add("filter__chip--active");
        else other.classList.remove("filter__chip--active");
      });

      Array.prototype.forEach.call(docItems, function (item) {
        var hit = key === "all" || item.getAttribute("data-doc-state") === key;
        if (hit) item.removeAttribute("hidden");
        else item.setAttribute("hidden", "");
      });
    });
  });

  /* ==========================================================================
     30. 电子签章管理（第 8 批：28-doc-seal）
     ==========================================================================
     新建面板 + 设为默认 + 删除。删除走 toast 不真删（设计稿不做数据操作）。
     ========================================================================== */
  var sealNewMask = document.querySelector("[data-seal-new-mask]");
  var sealNewSheet = document.querySelector("[data-seal-new-sheet]");

  function setSealNewSheet(open) {
    toggleLayer(sealNewMask, open);
    toggleLayer(sealNewSheet, open);
  }

  if (sealNewSheet) {
    Array.prototype.forEach.call(
      document.querySelectorAll("[data-seal-new]"),
      function (btn) {
        btn.addEventListener("click", function () {
          setSealNewSheet(true);
        });
      }
    );
    Array.prototype.forEach.call(
      document.querySelectorAll("[data-seal-new-close]"),
      function (btn) {
        btn.addEventListener("click", function () {
          setSealNewSheet(false);
        });
      }
    );
    if (sealNewMask) {
      sealNewMask.addEventListener("click", function () {
        setSealNewSheet(false);
      });
    }
    var sealNewConfirm = document.querySelector("[data-seal-new-confirm]");
    if (sealNewConfirm) {
      sealNewConfirm.addEventListener("click", function () {
        setSealNewSheet(false);
        showToast("签章已保存");
      });
    }
  }

  /* 走查开关：hash #open-seal 直接打开新建面板 */
  if (walkHash === "open-seal") setSealNewSheet(true);

  Array.prototype.forEach.call(
    document.querySelectorAll("[data-seal-default]"),
    function (btn) {
      btn.addEventListener("click", function () {
        showToast("已设为默认签章");
      });
    }
  );

  Array.prototype.forEach.call(
    document.querySelectorAll("[data-seal-delete]"),
    function (btn) {
      btn.addEventListener("click", function () {
        showToast("签章已删除");
      });
    }
  );

  var sealEdit = document.querySelector("[data-seal-edit]");
  if (sealEdit) {
    sealEdit.addEventListener("click", function () {
      showToast("查看签章详情（演示）");
    });
  }
})();
