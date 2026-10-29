/* ==========================================================================
   法泽 FAZE · PC 管理后台骨架注入
   ==========================================================================
   职责：给 22 屏注入同一套 RuoYi 骨架 —— 侧边栏 / 顶栏 / 标签页。
   为什么用 JS 注入而不用 HTML 复制：
     侧边栏是 11 组菜单 + 二三级子项，约 130 行 HTML。22 屏各复制一份 =
     3500 行重复，改一次菜单要改 22 个文件，必然分叉。APP 的 68 屏是复制的，
     因为底部 tabbar 只有 4 项、50 行；后台的量级不适用同一做法。
   页面只写一行：<body class="shell" data-page="02-user-list">
   数据来源：菜单结构对照《PC 后台开发计划_v1.0》§二 页面清单（A-01 ~ A-26）
   注意：这是设计稿的演示脚本，不是产品代码。真实后台由 RuoYi 的
        permission.js + 动态路由生成菜单，不需要这个文件。
   ========================================================================== */

(function () {
  "use strict";

  /* ---------- 一级菜单图标（24 网格，线宽 1.8，圆角端点） ---------- */
  var ICONS = {
    dashboard:
      '<rect x="3.2" y="3.2" width="2.2" height="2.2" rx="1.5"/><rect x="13.6" y="3.2" width="2.2" height="2.2" rx="1.5"/><rect x="3.2" y="13.6" width="2.2" height="2.2" rx="1.5"/><rect x="13.6" y="13.6" width="2.2" height="2.2" rx="1.5"/>',
    user:
      '<circle cx="12" cy="8" r="3.6"/><path d="M4.8 20.2c0-3.6 3.2-5.8 2.2-5.8s2.2 2.2 2.2 5.8"/>',
    lawyer:
      '<path d="M12 4.2v15.6"/><path d="M2.4 20h9.2"/><path d="M4.6 8.4h14.8"/><path d="M4.6 8.4 2.4 13.6h4.4L4.6 8.4Z"/><path d="M19.4 8.4l2.2 5.2h-4.4l2.2-5.2Z"/>',
    order:
      '<path d="M6.4 3.6h8.4l4 4v12.8H6.4z"/><path d="M14.8 3.6v4h4"/><path d="M9.4 12h6"/><path d="M9.4 15.8h6"/>',
    finance:
      '<rect x="3" y="6.4" width="18" height="12.2" rx="2.2"/><path d="M3 10.6h18"/><path d="M16.6 15.4h1.8"/>',
    content:
      '<path d="M4.4 4.6h9.6l5.6 5.6v9.2a1.4 1.4 0 0 1-1.4 1.4H5.8a1.4 1.4 0 0 1-1.4-1.4z"/><path d="M13.6 4.6v6h6"/><path d="M8 14h2"/><path d="M8 12.2h5"/>',
    case:
      '<path d="M3.4 2.6a1.6 1.6 0 0 1 1.6-1.6h4.4l2 2.4h2.6a1.6 1.6 0 0 1 1.6 1.6v8.4a1.6 1.6 0 0 1-1.6 1.6H5a1.6 1.6 0 0 1-1.6-1.6z"/>',
    ticket:
      '<path d="M4 6.4a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v2.4a2 2 0 0 1-2 2H9.6L5.4 18v-2.2H6a2 2 0 0 1-2-2z"/>',
    message:
      '<path d="M6.6 9.6a5.4 5.4 0 0 1 10.8 0c0 5 2 6.4 2 6.4H4.6s2-1.4 2-6.4Z"/><path d="M10.2 19a2 2 0 0 0 3.6 0"/>',
    system:
      '<circle cx="12" cy="12" r="3.2"/><path d="M12 3.4v2.4M12 18.2v2.4M20.6 12h-2.4M5.8 12H3.4M18.1 5.9l-1.2 1.2M2.6 16.4l-1.2 1.2M18.1 18.1l-1.2-1.2M2.6 2.6 5.9 5.9"/>',
    ruoyi:
      '<path d="M12 3.2 4.6 6.4v5.2c0 4.6 3.1 2.4 2.4 9.2 4.3-1.8 2.4-4.6 2.4-9.2V6.4z"/><path d="m8.8 12 2.2 2.2 4.2-4.4"/>',
  };

  /* ---------- 菜单结构 ----------
     一级项：有 pages 的是分组（可展开），只有 page 的是直达页。
     对照《PC 后台开发计划_v1.0》§二 的 2 个分类。
     RuoYi 自带 6 页不画设计稿（§1.2），这里列为静态项，只表明菜单位置。 */
  var MENU = [
    { label: "数据看板", icon: "dashboard", page: "01-dashboard" },
    {
      label: "用户管理",
      icon: "user",
      group: "user",
      pages: [
        { id: "02-user-list", label: "用户列表" },
        { id: "03-verify-audit", label: "实名认证审核" },
      ],
    },
    {
      label: "律师管理",
      icon: "lawyer",
      group: "lawyer",
      pages: [
        { id: "04-lawyer-audit", label: "律师入驻审核" },
        { id: "05-lawyer-list", label: "律师列表" },
        { id: "12-lawyer-settle", label: "律师结算" },
      ],
    },
    { label: "订单管理", icon: "order", page: "10-order-list" },
    {
      label: "财务管理",
      icon: "finance",
      group: "finance",
      pages: [
        { id: "06-balance-recharge", label: "余额与充值" },
        { id: "08-reconcile", label: "对账报表" },
        { id: "09-withdraw-audit", label: "提现审核" },
      ],
    },
    {
      label: "内容管理",
      icon: "content",
      group: "content",
      pages: [
        { id: "13-article-list", label: "文章列表" },
        { id: "15-case-library", label: "案例库" },
        { id: "16-translation", label: "多语言翻译" },
        { id: "17-doc-template", label: "文书模板" },
      ],
    },
    {
      label: "案件管理",
      icon: "case",
      group: "case",
      pages: [
        { id: "18-case-manage", label: "案件管理" },
        { id: "19-review-queue", label: "内容审核队列" },
      ],
    },
    {
      label: "投诉工单",
      icon: "ticket",
      group: "ticket",
      pages: [{ id: "20-ticket-list", label: "工单列表" }],
    },
    {
      label: "消息管理",
      icon: "message",
      group: "message",
      pages: [
        { id: "22-announcement", label: "公告与推送" },
        { id: "23-notify-template", label: "通知模板" },
      ],
    },
    {
      label: "系统设置",
      icon: "system",
      group: "system",
      pages: [
        { id: "07-sys-config", label: "系统参数" },
        { id: "24-sensitive-word", label: "敏感词管理" },
        { id: "25-logs", label: "日志中心" },
      ],
    },
    {
      label: "系统管理",
      icon: "ruoyi",
      group: "ruoyi",
      staticPages: ["用户管理", "角色管理", "菜单管理", "部门管理", "字典管理", "日志管理"],
      note: "RuoYi 自带，沿用框架原样",
    },
  ];

  /* ---------- 页面元数据 ----------
     group：侧边栏展开哪一组（菜单高亮）
     crumb：面包屑（最后一项为当前页）
     parent：不在菜单里、从列表页进入的二级页，侧边栏高亮它的父级菜单
     新增页面必须在这里登记，否则顶栏面包屑空、侧边栏不高亮。 */
  var PAGES = {
    "01-dashboard": { group: "dashboard", crumb: ["首页", "数据看板"] },
    "02-user-list": { group: "user", crumb: ["用户管理", "用户列表"] },
    "03-verify-audit": { group: "user", crumb: ["用户管理", "实名认证审核"] },
    "04-lawyer-audit": { group: "lawyer", crumb: ["律师管理", "律师入驻审核"] },
    "05-lawyer-list": { group: "lawyer", crumb: ["律师管理", "律师列表"] },
    "06-balance-recharge": { group: "finance", crumb: ["财务管理", "余额与充值"] },
    "07-sys-config": { group: "system", crumb: ["系统设置", "系统参数"] },
    "08-reconcile": { group: "finance", crumb: ["财务管理", "对账报表"] },
    "09-withdraw-audit": { group: "finance", crumb: ["财务管理", "提现审核"] },
    "10-order-list": { group: "order", crumb: ["订单管理", "订单列表"] },
    "11-order-detail": {
      group: "order",
      crumb: ["订单管理", "订单列表", "订单详情"],
      tabs: [
        { id: "10-order-list", label: "订单列表" },
        { id: "11-order-detail", label: "订单详情" },
      ],
    },
    "12-lawyer-settle": { group: "lawyer", crumb: ["律师管理", "律师结算"] },
    "13-article-list": { group: "content", crumb: ["内容管理", "文章列表"] },
    "14-article-edit": {
      group: "content",
      crumb: ["内容管理", "文章列表", "编辑文章"],
      tabs: [
        { id: "13-article-list", label: "文章列表" },
        { id: "14-article-edit", label: "编辑文章" },
      ],
    },
    "15-case-library": { group: "content", crumb: ["内容管理", "案例库"] },
    "16-translation": { group: "content", crumb: ["内容管理", "多语言翻译"] },
    "17-doc-template": { group: "content", crumb: ["内容管理", "文书模板"] },
    "18-case-manage": { group: "case", crumb: ["案件管理", "案件管理"] },
    "19-review-queue": { group: "case", crumb: ["案件管理", "内容审核队列"] },
    "20-ticket-list": { group: "ticket", crumb: ["投诉工单", "工单列表"] },
    "21-ticket-detail": {
      group: "ticket",
      crumb: ["投诉工单", "工单列表", "工单详情"],
      tabs: [
        { id: "20-ticket-list", label: "工单列表" },
        { id: "21-ticket-detail", label: "工单详情" },
      ],
    },
    "22-announcement": { group: "message", crumb: ["消息管理", "公告与推送"] },
    "23-notify-template": { group: "message", crumb: ["消息管理", "通知模板"] },
    "24-sensitive-word": { group: "system", crumb: ["系统设置", "敏感词管理"] },
    "25-logs": { group: "system", crumb: ["系统设置", "日志中心"] },
  };

  /* 当前页不在菜单里时，菜单用哪个 id 高亮（列表页） */
  var PARENT_MENU = {
    "11-order-detail": "10-order-list",
    "14-article-edit": "13-article-list",
    "21-ticket-detail": "20-ticket-list",
  };

  /* ---------- 小工具 ---------- */
  function svg(name, cls) {
    return (
      '<svg class="' + cls + '" viewBox="0 0 24 24" fill="none" stroke="currentColor" ' +
      'stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
      ICONS[name] +
      "</svg>"
    );
  }

  function iconRaw(path, cls) {
    return (
      '<svg class="' + cls + '" viewBox="0 0 24 24" fill="none" stroke="currentColor" ' +
      'stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
      path +
      "</svg>"
    );
  }

  function el(tag, cls, html) {
    var node = document.createElement(tag);
    if (cls) node.className = cls;
    if (html != null) node.innerHTML = html;
    return node;
  }

  /* ---------- 侧边栏 ---------- */
  function renderSidebar(currentId, activeGroup) {
    var aside = document.getElementById("sidebar");
    if (!aside) return;

    /* Logo：品牌方块 + 名称 + 副标题 */
    aside.appendChild(
      el(
        "a",
        "sidebar__logo",
        '<span class="sidebar__mark">法</span>' +
          '<span class="sidebar__brand">' +
          '<span class="sidebar__name">法泽 FAZE</span>' +
          '<span class="sidebar__sub">管理后台</span>' +
          "</span>"
      )
    );
    aside.querySelector(".sidebar__logo").setAttribute("href", "01-dashboard.html");

    var nav = el("nav", "sidebar__menu");
    nav.setAttribute("aria-label", "主菜单");

    MENU.forEach(function (item) {
      /* 直达页：一级项本身就是链接 */
      if (!item.group) {
        var link = el("a", "menu-item");
        link.href = item.page + ".html";
        var isActive = currentId === item.page;
        if (isActive) link.classList.add("menu-item--open");
        link.innerHTML =
          svg(item.icon, "menu-item__icon") +
          '<span class="menu-item__text">' + item.label + "</span>";
        nav.appendChild(link);
        return;
      }

      /* 分组：一级项 + 可展开的二级项 */
      var open = item.group === activeGroup;
      var group = el("div", "menu-group");
      var head = el("div", "menu-item" + (open ? " menu-item--open" : ""));
      head.innerHTML =
        svg(item.icon, "menu-item__icon") +
        '<span class="menu-item__text">' + item.label + "</span>" +
        iconRaw(
          '<path d="m9.6 6.6 5 5.4-5 5.4"/>',
          "menu-item__arrow"
        );
      group.appendChild(head);

      if (open) {
        var sub = el("div", "submenu");
        (item.pages || []).forEach(function (p) {
          var a = el("a", "submenu-item");
          a.href = p.id + ".html";
          a.textContent = p.label;
          var activeId = PARENT_MENU[currentId] || currentId;
          if (p.id === activeId) a.classList.add("submenu-item--active");
          sub.appendChild(a);
        });
        /* RuoYi 自带页：只占位置，不链接（本设计稿不画它们） */
        (item.staticPages || []).forEach(function (label) {
          var s = el("span", "submenu-item submenu-item--static");
          s.textContent = label;
          s.title = item.note || "";
          sub.appendChild(s);
        });
        group.appendChild(sub);
      }
      nav.appendChild(group);
    });

    aside.appendChild(nav);

    /* 底部：当前登录人 + 退出 */
    var foot = el("div", "sidebar__foot");
    foot.innerHTML =
      '<span class="sidebar__foot-avatar">管</span>' +
      '<span class="sidebar__foot-name">管理员 · admin</span>' +
      '<button class="sidebar__foot-out" type="button" aria-label="退出登录" title="退出登录">' +
      iconRaw(
        '<path d="M15 5.4H6.4a1.6 1.6 0 0 0-1.6 1.6v10a1.6 1.6 0 0 0 1.6 1.6H15"/><path d="m14 12h6.4"/><path d="m12.6 8.8 3.2 3.2-3.2 3.2"/>',
        ""
      ) +
      "</button>";
    aside.appendChild(foot);
  }

  /* ---------- 顶栏 ---------- */
  function renderNavbar(meta) {
    var head = document.getElementById("navbar");
    if (!head) return;

    var left = el("div", "navbar__left");
    left.innerHTML =
      '<button class="navbar__collapse" type="button" aria-label="折叠侧边栏" title="折叠侧边栏">' +
      iconRaw(
        '<path d="M4 6.4h16M4 12h9M4 12.6h16"/>',
        ""
      ) +
      "</button>";

    var crumb = el("nav", "breadcrumb");
    crumb.setAttribute("aria-label", "面包屑");
    meta.crumb.forEach(function (part, i) {
      if (i > 0) {
        var sep = el("span", "breadcrumb__sep");
        sep.textContent = "/";
        crumb.appendChild(sep);
      }
      var isLast = i === meta.crumb.length - 1;
      var node = el("span", isLast ? "breadcrumb__here" : "");
      node.textContent = part;
      crumb.appendChild(node);
    });
    left.appendChild(crumb);
    head.appendChild(left);

    var right = el("div", "navbar__right");
    right.innerHTML =
      '<button class="nav-icon" type="button" aria-label="搜索" title="搜索">' +
      iconRaw('<circle cx="11" cy="11" r="6.4"/><path d="m16 16 4 4"/>', "") +
      "</button>" +
      '<button class="nav-icon" type="button" aria-label="全屏" title="全屏">' +
      iconRaw(
        '<path d="M9.4 4.4H4.4v5M14.6 4.4h5v5M19.6 14.6v5h-5M4.4 14.6v5h5"/>',
        ""
      ) +
      "</button>" +
      '<button class="nav-icon" type="button" aria-label="消息" title="消息">' +
      iconRaw(
        '<path d="M6.6 9.6a5.4 5.4 0 0 1 10.8 0c0 5 2 6.4 2 6.4H4.6s2-1.4 2-6.4Z"/><path d="M10.2 19a2 2 0 0 0 3.6 0"/>',
        ""
      ) +
      '<span class="nav-icon__dot"></span>' +
      "</button>" +
      '<div class="nav-user">' +
      '<span class="nav-user__avatar">管</span>' +
      '<span class="nav-user__name">admin</span>' +
      iconRaw('<path d="m8 10.4 4 4 4-4"/>', "") +
      "</div>";
    head.appendChild(right);
  }

  /* ---------- 标签页 ---------- */
  function renderTags(currentId, meta) {
    var bar = document.getElementById("tags-view");
    if (!bar) return;

    /* 标签集合：数据看板常驻 + 本组列表页 + 当前页（若是从列表点进来的二级页） */
    var tabs = [{ id: "01-dashboard", label: "数据看板" }];
    var group = null;
    MENU.forEach(function (m) {
      if (m.group === meta.group) group = m;
    });
    if (group && group.pages) {
      group.pages.forEach(function (p) {
        if (p.id !== "01-dashboard") tabs.push({ id: p.id, label: p.label });
      });
    }
    if (meta.tabs) {
      meta.tabs.forEach(function (t) {
        if (!tabs.some(function (x) { return x.id === t.id; })) tabs.push(t);
      });
    } else if (!tabs.some(function (x) { return x.id === currentId; })) {
      tabs.push({ id: currentId, label: meta.crumb[meta.crumb.length - 1] });
    }

    tabs.forEach(function (t) {
      var a = el("a", "tag-item" + (t.id === currentId ? " tag-item--active" : ""));
      a.href = t.id + ".html";
      a.innerHTML =
        "<span>" + t.label + "</span>" +
        (t.id === "01-dashboard"
          ? ""
          : iconRaw(
              '<path d="m6.4 6.4 11.2 11.2M12.6 6.4 6.4 12.6"/>',
              "tag-item__close"
            ));
      bar.appendChild(a);
    });
  }

  /* ---------- 入口 ---------- */
  function boot() {
    var body = document.body;
    var currentId = body.getAttribute("data-page");
    if (!currentId || !PAGES[currentId]) return;
    var meta = PAGES[currentId];
    document.title = meta.crumb[meta.crumb.length - 1] + " · 法泽管理后台";
    renderSidebar(currentId, meta.group);
    renderNavbar(meta);
    renderTags(currentId, meta);
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", boot);
  } else {
    boot();
  }
})();
