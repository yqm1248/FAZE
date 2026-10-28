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
    "13-article.html"
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
    var known = ["normal", "loading", "empty", "error"];
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
    favBtn.addEventListener("click", function () {
      var on = favBtn.classList.toggle("is-on");
      favBtn.setAttribute("aria-pressed", on ? "true" : "false");
      favBtn.setAttribute("aria-label", on ? "取消收藏" : "收藏文章");
      showToast(on ? "已收藏，可在「我的 - 我的收藏」查看" : "已取消收藏");
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
})();
