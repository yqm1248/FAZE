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
    "11-privacy.html"
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
})();
