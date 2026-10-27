  /* ---------- 0. the rule table: every selector the script uses lives here ---------- */
  var RULES = {
    // display:none groups (one stylesheet). Selectors with :has() go in their own rule so an
    // engine without :has() support only drops those, never the plain ones.
    hide: {
      ads: [
        'ad-slot-renderer', 'ytm-ad-slot-renderer', 'ytm-promoted-sparkles-web-renderer', 'ytm-promoted-sparkles-text-search-renderer',
        'ytm-promoted-video-renderer', 'ytm-promoted-item-renderer', 'ytm-compact-promoted-item-renderer', 'ytm-in-feed-ad-layout-renderer',
        'ytm-display-ad-renderer', 'ytm-companion-ad-renderer', 'ytm-action-companion-ad-renderer', 'ytm-companion-slot',
        'ytm-video-masthead-ad-v3-renderer', 'ytm-brand-video-singleton-renderer', 'ytm-brand-video-shelf-renderer',
        'ytm-primetime-promo-renderer', 'ytm-merch-shelf-renderer', 'ytm-product-list-renderer', 'ytm-shopping-carousel-renderer',
        'ytm-offer-module-renderer', 'ytm-ticket-shelf-renderer', 'ytm-ad-badge-renderer',
        '.ytp-ad-overlay-container', '.ytp-ad-player-overlay-layout', '.ytp-ad-persistent-progress-bar-container', '.ytp-ad-action-interstitial', '.ytp-ad-text-overlay', '.ytp-ad-image-overlay', 'a[href^="intent:"]', 'ytm-open-app-promo-renderer',
        'ytm-rich-item-renderer:has(> ad-slot-renderer)', 'ytm-rich-item-renderer:has(> ytm-ad-slot-renderer)',
        'ytm-item-section-renderer:has(> lazy-list > ad-slot-renderer:only-child)',
        'ytm-rich-item-renderer:has(ytm-ad-badge-renderer)', 'ytm-compact-video-renderer:has(ytm-ad-badge-renderer)'
      ],
      // "Includes paid promotion" strip (7)
      paid: ['ytm-paid-content-overlay-renderer', '.ytp-paid-content-overlay', '.ytmPaidContentOverlayHost', 'ytm-paid-content-overlay-view-model'],
      // YouTube Premium banners / trial prompts (12)
      premium: [
        'ytm-mealbar-promo-renderer', 'yt-mealbar-promo-renderer', 'ytm-statement-banner-renderer', 'ytm-banner-promo-renderer',
        'ytm-background-promo-renderer', 'ytm-upsell-dialog-renderer', 'yt-upsell-dialog-renderer',
        'ytm-popup-container:has(> ytm-mealbar-promo-renderer)', 'ytm-popup-container:has(> ytm-upsell-dialog-renderer)'
      ],
      // creator end screen elements + info cards inside the player (8); the autoplay countdown is kept
      endscreen: [
        '.html5-video-player .ytp-ce-element', '.html5-video-player .ytp-ce-covering-overlay', '.html5-video-player .ytp-ce-element-shadow',
        '.html5-video-player .ytp-ce-expanding-overlay', '.html5-video-player .ytp-cards-teaser', '.html5-video-player .ytp-cards-button',
        '.html5-video-player .ytp-cards-teaser-box', '.html5-video-player .iv-branding', '.html5-video-player .video-annotations',
        '.html5-video-player .ytp-suggested-action', '.html5-video-player .ytp-featured-product'
      ],
      // action button row under the video: like/dislike/share/save/remix/download/clip/thanks
      actionRow: ['ytm-slim-video-action-bar-renderer', '.slim-video-action-bar-actions', 'ytm-video-actions-bar', 'ytw-slim-video-action-bar-view-model'],
      shorts: [
        'ytm-reel-shelf-renderer', 'ytm-shorts-lockup-view-model', 'ytm-shorts-lockup-view-model-v2', 'ytm-reel-item-renderer',
        '.big-shorts-singleton', 'ytm-reel-video-renderer', 'a[role="tab"][href$="/shorts"]', '.scbrr-tab[href$="/shorts"]',
        'ytm-pivot-bar-item-renderer:has(.pivot-shorts)',
        'ytm-rich-section-renderer:has(ytm-reel-shelf-renderer)', 'ytm-rich-section-renderer:has(ytm-shorts-lockup-view-model)',
        'ytm-rich-section-renderer:has(ytm-shorts-lockup-view-model-v2)', 'grid-shelf-view-model:has(ytm-shorts-lockup-view-model)',
        'grid-shelf-view-model:has(ytm-shorts-lockup-view-model-v2)',
        'ytm-rich-item-renderer:has(a[href^="/shorts/"])', 'ytm-video-with-context-renderer:has(a[href^="/shorts/"])',
        'ytm-compact-video-renderer:has(a[href^="/shorts/"])'
      ],
      help: [
        'ytm-multi-page-menu-renderer a[href*="support.google.com"]', 'ytm-multi-page-menu-renderer a[href*="feedback"]',
        'ytm-compact-link-renderer:has(a[href*="support.google.com"])', 'ytm-compact-link-renderer:has(a[href*="feedback"])',
        'ytm-compact-link-renderer:has(a[href*="reporthistory"])'
      ],
      // pivot "create / upload" entry (126), in-player channel watermark (163)
      pivotExtra: ['ytm-pivot-bar-item-renderer:has(a[href*="/upload"])', 'ytm-pivot-bar-item-renderer:has(.pivot-create)'],
      watermark: ['.html5-video-player .ytp-watermark', '.html5-video-player .annotation-type-custom.iv-branding'],
      own: ['[data-ytl-hide]', '[data-ytl-seen]', '[data-ytl-filter]']
    },
    // recommendation wall at the end of a video (36): only the grid, never the autoplay countdown
    endWall: ['.html5-video-player .ytp-endscreen-content', '.html5-video-player .ytp-endscreen-previous',
      '.html5-video-player .ytp-endscreen-next'],
    captionSeg: '.ytp-caption-segment',
    accentFill: ['.ytp-play-progress', '.ytp-swatch-background-color', '.ytm-progress-bar-played', '.YtProgressBarLineProgressBarPlayed',
      '.ytProgressBarLineProgressBarPlayed', '.ytp-scrubber-button'],
    amoledBg: ['body', 'ytm-app', 'ytm-mobile-topbar-renderer', '.mobile-topbar-header', 'ytm-pivot-bar-renderer', '#header-bar',
      'ytm-browse', 'ytm-watch', 'ytm-single-column-watch-next-results-renderer', 'ytm-engagement-panel'],
    menuBtn: ['ytm-media-item .media-item-menu button', 'ytm-video-with-context-renderer ytm-menu-renderer button',
      'ytm-compact-video-renderer ytm-menu-renderer button'],
    searchExtras: ['ytm-horizontal-card-list-renderer', '[data-ytl-searchx]'],
    subsPosts: ['ytm-backstage-post-thread-renderer', 'ytm-post-renderer', 'ytm-rich-section-renderer:has(ytm-backstage-post-thread-renderer)',
      'ytm-rich-item-renderer:has(ytm-backstage-post-thread-renderer)', 'ytm-rich-item-renderer:has(ytm-post-renderer)'],
    homeShelves: ['ytm-rich-section-renderer'],
    captionWin: '.caption-window',
    // ad fallback: black cover over the picture while an ad still plays (4)
    adCover: ['#movie_player.ad-showing video', '#movie_player.ad-interrupting video', '#movie_player.ytl-ad video'],
    adCoverPlayer: ['#movie_player.ad-showing', '#movie_player.ad-interrupting', '#movie_player.ytl-ad'],
    // smoothness rules, active only while html[data-ytl-perf] is set (settings toggle)
    perf: {
      cards: ['ytm-rich-item-renderer', 'ytm-video-with-context-renderer'],             // 49/50
      compact: ['ytm-compact-video-renderer', 'ytm-playlist-video-renderer'],
      // 51; scoped to the app root (258) so the substring tests never run on <head> / popups outside it
      noAnim: ['ytm-app [class*="skeleton"]', 'ytm-app [class*="Skeleton"]', 'ytm-app [class*="shimmer"]', 'ytm-app [class*="Shimmer"]'],
      noRipple: ['yt-touch-feedback-shape', '.yt-spec-touch-feedback-shape', '.ytSpecTouchFeedbackShapeHost']
    },
    sel: {
      video: 'video.video-stream',
      adSkip: '.ytp-ad-skip-button-modern, .ytp-skip-ad-button, .ytp-ad-skip-button, .ytp-ad-skip-button-container,' +
        ' .ytp-ad-skip-button-slot button, button[id^="skip-button"], .ytm-skip-ad-button, ytm-skip-ad-button button',
      watchedBar: '.ytThumbnailOverlayProgressBarHostWatchedProgressBarSegment, .thumbnail-overlay-resume-playback-progress,' +
        ' ytm-thumbnail-overlay-resume-playback-renderer [style*="width"]',
      feedItem: 'ytm-rich-item-renderer, ytm-video-with-context-renderer, ytm-compact-video-renderer, ytm-playlist-video-renderer',
      // short labels on a card that mark it as sponsored (285): matched by exact text only
      adLabel: 'badge-shape, .badge-shape-wiz__text, ytm-badge, ytm-badge-supported-renderer, [class*="ad-badge" i], [class*="AdBadge"]',
      menu: 'ytm-menu-service-item-renderer, ytm-menu-navigation-item-renderer, ytm-menu-item, ytm-compact-link-renderer,' +
        ' [role="menuitem"], .menu-item, ytm-bottom-sheet-renderer yt-list-item-view-model,' +
        ' bottom-sheet-container yt-list-item-view-model, ytm-multi-page-menu-renderer yt-list-item-view-model,' +
        ' ytm-account-item-renderer, ytm-setting-generic-category',
      shortsText: 'ytm-pivot-bar-item-renderer, [role="tab"], .scbrr-tab, yt-tab-shape, ytm-chip-cloud-chip-renderer,' +
        ' yt-chip-cloud-chip-renderer, chip-shape',
      stillDialog: 'ytm-confirm-dialog-renderer, yt-confirm-dialog-renderer',
      anyDialog: 'ytm-dialog, tp-yt-paper-dialog, [role="dialog"], .dialog-container',
      autonav: '.ytm-autonav-toggle-button-container',
      playerError: '.ytp-error, ytm-player-error-message-renderer',
      playPause: '.player-control-play-pause-icon, .ytp-play-button, button[aria-label*="ause"]',
      contentRoot: 'ytm-app',
      relatedItem: 'ytm-video-with-context-renderer, ytm-compact-video-renderer',
      byline: '[class*="byline" i], ytm-badge-and-byline-renderer, .subhead',
      ownerLink: 'ytm-slim-owner-renderer a[href], .slim-owner-icon-and-title[href], ytm-video-owner-renderer a[href]',
      cardTitle: '.media-item-headline, h3, h4, [class*="title" i]',
      timeBadge: 'ytm-thumbnail-overlay-time-status-renderer, .badge-shape-wiz__text, badge-shape, [class*="time-status" i]',
      cardMeta: '.media-item-metadata, [class*="metadata" i], ytm-badge-and-byline-renderer',
      cardMenuBtn: 'ytm-menu-renderer button, .media-item-menu button',
      searchInput: 'input.searchbox-input, ytm-searchbox input, input[name="search_query"]',
      searchList: 'ytm-search ytm-section-list-renderer, ytm-section-list-renderer',
      searchShelf: 'ytm-shelf-renderer, ytm-horizontal-card-list-renderer, ytm-rich-shelf-renderer',
      shelfTitle: 'h2, h3, .shelf-header, [class*="title" i]',
      maxresImg: 'img[src*="/maxresdefault"]',
      // batch 6
      // comments / description panels on the watch page (264): mutations in here never touch a list card
      quietZone: 'ytm-engagement-panel, ytm-comment-section-renderer, ytm-comment-thread-renderer, ytm-comments-entry-point-header-renderer',
      // a result that is an auto-generated mix (333): its link carries list=RD…
      mixLink: 'a[href*="list=RD"]',
      mixCard: 'ytm-compact-radio-renderer, ytm-radio-renderer, ytm-compact-playlist-renderer, ytm-playlist-renderer,' +
        ' ytm-video-with-context-renderer, ytm-compact-video-renderer, ytm-rich-item-renderer, yt-lockup-view-model',
      listImg: 'img:not([decoding])',
      playerBox: '#player-container-id'
    },
    // every on-page text the script matches, by language, in one place (382); mode: full = the whole
    // text, start = the text starts with it, any = anywhere. rules.json "text" can append plain words.
    text: {
      still: ['any', ['continue watching', 'still watching', 'still there', 'are you there', 'video paused', '还在看吗', '你还在吗', '继续观看', '仍在观看', '视频已暂停', '繼續觀看', '仍在觀看', '影片已暫停']],
      stillYes: ['full', ['是', '是的', '继续', '继续观看', '继续播放', '繼續', '繼續觀看', '繼續播放', 'yes', 'continue', 'continue watching', 'keep watching', 'i\'m still here', 'still watching']],
      stillNo: ['full', ['否', '不', '取消', '退出', '停止', 'no', 'cancel', 'exit', 'stop', 'dismiss']],
      retry: ['any', ['try again', 'playback id', 'error occurred', '重试', '重新整理', '稍后', '出错', '發生錯誤', '错误']],
      live: ['start', ['LIVE', '直播', '直播中', '正在直播', 'PREMIERE', 'PREMIERES?', '首映', '首播', 'UPCOMING', '即将', '即將', 'SCHEDULED']],
      searchExtra: ['any', ['people also', 'also watched', 'also search', 'for you', 'previously watched', 'related to your search', '人们还', '大家还', '其他人还', '为你推荐', '為你推薦', '相关搜索', '相關搜尋', '你看过', '看過的影片']],
      adLabel: ['full', ['赞助', '贊助', '广告', '廣告', '赞助商广告', '贊助商廣告', 'sponsored', 'ad', 'ads']],
      feedback: ['full', ['help', 'help ?(?:&|and) ?feedback', 'send feedback', 'feedback', 'give feedback', 'report', 'report history', 'report (?:video|playlist|channel|comment|ad|this ad)', 'why this ad\\??', '帮助', '帮助和反馈', '帮助与反馈', '帮助及反馈', '发送反馈', '提供反馈', '反馈', '意见反馈', '举报', '举报记录', '举报视频', '举报播放列表', '举报频道', '說明', '說明與意見回饋', '提供意見', '傳送意見回饋', '提供意見回饋', '意見回饋', '檢舉', '檢舉記錄', '檢舉影片']]
    },
    // one-tap sleep mode (340): comments only; related videos stay for the same-channel rule
    sleepHide: ['ytm-comment-section-renderer', 'ytm-comments-entry-point-header-renderer',
      'ytm-item-section-renderer[section-identifier="comments-entry-point"]', 'ytm-item-section-renderer[data-content-type="comments"]'],
    // search quick filters (125/328): YouTube's own sp values; rules.json "chips" can replace them
    chips: [['全部', ''], ['最新', 'CAI%3D'], ['只看视频', 'EgIQAQ%3D%3D'], ['本周', 'EgIIAw%3D%3D'], ['4–20 分钟', 'EgIYAw%3D%3D'], ['20 分钟以上', 'EgIYAg%3D%3D']]
  };

  function escRe(x) { return String(x).replace(/[.*+?^${}()|[\]\\\/]/g, '\\$&'); }
  /** Matcher for one entry of RULES.text (382), with the words rules.json appends. */
  function textRe(name) {
    var t = RULES.text[name], alts = t[1].slice();
    var add = remote.text && remote.text[name];
    if (add) for (var i = 0; i < add.length; i++) alts.push(escRe(add[i].trim()));
    var src = '(?:' + alts.join('|') + ')';
    if (t[0] === 'full') src = '^' + src + '$';
    else if (t[0] === 'start') src = '^' + src;
    return new RegExp(src, 'i');
  }

  function sheet() {
    var out = [];
    function rule(list, body) {
      var plain = [], has = [];
      for (var i = 0; i < list.length; i++) (list[i].indexOf(':has(') >= 0 ? has : plain).push(list[i]);
      if (plain.length) out.push(plain.join(',\n') + '{' + body + '}');
      for (var j = 0; j < has.length; j++) out.push(has[j] + '{' + body + '}');
    }
    function pre(list) { return list.map(function (s) { return 'html[data-ytl-perf] ' + s; }); }
    var H = RULES.hide;
    for (var k in H) if (H.hasOwnProperty(k)) rule(H[k], 'display:none!important');
    rule(RULES.adCover, 'opacity:0!important');
    rule(RULES.adCoverPlayer, 'background:#000!important');
    // placeholder height from the screen width (265): 16:9 picture + title block, the real height is remembered (auto)
    rule(pre(RULES.perf.cards), 'content-visibility:auto;contain-intrinsic-size:auto 360px;contain-intrinsic-size:auto min(calc(56.25vw + 112px),720px);contain:layout style');
    rule(pre(RULES.perf.compact), 'content-visibility:auto;contain-intrinsic-size:auto 110px;contain:layout style');
    rule(pre(RULES.perf.noAnim), 'animation:none!important;transition:none!important');
    rule(pre(RULES.perf.noRipple), 'display:none!important');
    rule(RULES.endWall.map(function (x) { return 'html[data-ytl-noendwall] ' + x; }), 'display:none!important');
    // caption style (155): size by transform (no layout change), background opacity
    var cw = RULES.captionWin, cs = RULES.captionSeg;
    out.push('html[data-ytl-subsize="l"] ' + cw + '{transform:scale(1.25);transform-origin:50% 100%}');
    out.push('html[data-ytl-subsize="xl"] ' + cw + '{transform:scale(1.5);transform-origin:50% 100%}');
    out.push('html[data-ytl-subsize="s"] ' + cw + '{transform:scale(.85);transform-origin:50% 100%}');
    out.push('html[data-ytl-subbg="half"] ' + cs + '{background:rgba(8,8,8,.45)!important}');
    out.push('html[data-ytl-subbg="none"] ' + cs + '{background:transparent!important;text-shadow:0 0 3px #000,0 0 2px #000,1px 1px 2px #000}');
    // ---- batch 3 look (each behind its own html attribute, so a setting off means no rule applies) ----
    // design tokens for our own controls (164)
    out.push(':root{--ytl-accent:#2f6bff;--ytl-accent-text:#6b95ff;--ytl-radius:12px;--ytl-surface:rgba(33,33,33,.96);--ytl-anim:200ms}');
    out.push('html[data-ytl-accent]{--ytl-accent:var(--ytl-accent-pick);--ytl-accent-text:var(--ytl-accent-pick)}');
    rule(RULES.accentFill.map(function (x) { return 'html[data-ytl-accent] ' + x; }), 'background:var(--ytl-accent)!important;background-color:var(--ytl-accent)!important');
    // pure black (91)
    out.push('html[data-ytl-amoled][dark]{--yt-spec-base-background:#000!important;--yt-spec-brand-background-solid:#000!important;' +
      '--yt-spec-brand-background-primary:#000!important;--yt-spec-general-background-a:#000!important;--yt-spec-general-background-b:#000!important;' +
      '--yt-spec-raised-background:#121212!important;--yt-spec-menu-background:#121212!important;background:#000!important}');
    rule(RULES.amoledBg.map(function (x) { return 'html[data-ytl-amoled][dark] ' + x; }), 'background-color:#000!important');
    // compact list (108): small thumbnail left, text right
    out.push('html[data-ytl-compact] ytm-rich-item-renderer ytm-media-item{display:flex!important;flex-direction:row!important;align-items:flex-start;padding:6px 12px!important;margin:0!important}');
    out.push('html[data-ytl-compact] ytm-rich-item-renderer ytm-media-item>.media-item-thumbnail-container,' +
      'html[data-ytl-compact] ytm-rich-item-renderer ytm-media-item>a:first-child{flex:0 0 42%;max-width:42%;border-radius:8px;overflow:hidden}');
    out.push('html[data-ytl-compact] ytm-rich-item-renderer ytm-media-item>.details{flex:1 1 auto;min-width:0;padding:0 0 0 10px!important;margin:0!important}');
    out.push('html[data-ytl-compact] ytm-rich-item-renderer ytm-media-item .media-channel{display:none!important}');
    out.push('html[data-ytl-compact][data-ytl-perf] ytm-rich-item-renderer{contain-intrinsic-size:auto 120px;contain-intrinsic-size:auto max(96px,calc(23.625vw + 16px))}');
    // uniform cards (106/107/111)
    out.push('html[data-ytl-cards] .media-item-thumbnail-container,html[data-ytl-cards] yt-thumbnail-view-model{border-radius:12px;overflow:hidden}');
    out.push('html[data-ytl-cards] .media-item-headline,html[data-ytl-cards] ytm-media-item h3{display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;font-weight:500}');
    out.push('html[data-ytl-cards] ' + RULES.sel.watchedBar.split(',').join(',html[data-ytl-cards] ') + '{min-height:4px}');
    out.push('html[data-ytl-cards] ytm-thumbnail-overlay-time-status-renderer,html[data-ytl-cards] .badge-shape-wiz--thumbnail-default{font-weight:600}');
    // dim thumbnails in dark mode (110)
    out.push('html[dark][data-ytl-dimthumb] .media-item-thumbnail-container img,html[dark][data-ytl-dimthumb] yt-thumbnail-view-model img{filter:brightness(.88)}');
    // bigger hit area for the card "more" button without moving anything (122)
    rule(RULES.menuBtn, 'position:relative');
    rule(RULES.menuBtn.map(function (x) { return x + '::after'; }), "content:'';position:absolute;inset:-8px");
    // search extras (70), subscriptions videos only (63), home shelves (61)
    rule(RULES.searchExtras.map(function (x) { return 'html[data-ytl-nosearchx][data-ytl-page="search"] ' + x; }), 'display:none!important');
    rule(RULES.subsPosts.map(function (x) { return 'html[data-ytl-subsvideos][data-ytl-page="subs"] ' + x; }), 'display:none!important');
    rule(RULES.homeShelves.map(function (x) { return 'html[data-ytl-noshelves][data-ytl-page="home"] ' + x; }), 'display:none!important');
    // battery saver (147): animations off even when the smoothness CSS is off
    rule(RULES.perf.noAnim.map(function (x) { return 'html[data-ytl-lowpower] ' + x; }), 'animation:none!important;transition:none!important');
    // system "remove animations" (367): page transitions and our own fades off
    rule(RULES.perf.noAnim.map(function (x) { return 'html[data-ytl-lowmotion] ' + x; }), 'animation:none!important;transition:none!important');
    out.push('html[data-ytl-lowmotion] #ytl-sb,html[data-ytl-lowmotion] #ytl-chips a{transition:none!important;animation:none!important}');
    // very large text (378): card titles get a third line instead of being cut after two
    out.push('html[data-ytl-bigfont] .media-item-headline,html[data-ytl-bigfont] ytm-media-item h3{-webkit-line-clamp:3!important}');
    // update ready (137)
    out.push('html[data-ytl-update] #ytl-gear{position:relative}html[data-ytl-update] #ytl-gear::after{content:"";position:absolute;top:9px;right:9px;width:8px;height:8px;border-radius:50%;background:#ff4e45}');
    // search quick filters (125)
    out.push('#ytl-chips{display:flex;gap:8px;overflow-x:auto;padding:8px 12px;scrollbar-width:none}#ytl-chips::-webkit-scrollbar{display:none}' +
      '#ytl-chips a{flex:0 0 auto;padding:6px 12px;border-radius:8px;background:rgba(128,128,128,.18);color:inherit;font:500 13px/18px system-ui,sans-serif;text-decoration:none;white-space:nowrap}' +
      '#ytl-chips a[aria-current]{background:var(--ytl-accent);color:#fff}');
    // sleep badge (154): a tap adds time, a long press opens the timer choices (309); 40px touch target
    out.push('#ytl-badge{position:absolute;top:4px;left:4px;z-index:70;min-height:40px;display:flex;align-items:center;' +
      'padding:0 4px;background:none;border:0;margin:0;color:#fff;cursor:pointer;-webkit-tap-highlight-color:transparent}' +
      '#ytl-badge span{background:rgba(0,0,0,.6);font:500 12px/16px system-ui,sans-serif;padding:3px 8px;border-radius:10px;white-space:nowrap}');
    out.push('#ytl-tools{display:flex;align-items:center;justify-content:center;width:48px;height:48px;background:none;border:0;padding:0;margin:0 0 0 2px;color:inherit;fill:currentColor}');
    out.push('#ytl-gear{display:flex;align-items:center;justify-content:center;width:48px;height:48px;background:none;border:0;padding:0;margin:0 2px;color:inherit;fill:currentColor}');
    out.push('#ytl-sb{position:fixed;left:50%;bottom:76px;transform:translateX(-50%);z-index:2147483646;display:flex;align-items:center;gap:14px;' +
      'background:rgba(33,33,33,.96);color:#fff;font:14px/20px system-ui,sans-serif;padding:10px 14px 10px 16px;border-radius:10px;' +
      'box-shadow:0 2px 10px rgba(0,0,0,.5);max-width:90vw;white-space:nowrap}' +
      '#ytl-sb button{background:none;border:0;color:var(--ytl-accent-text);font:500 14px system-ui,sans-serif;padding:4px 6px;margin:0}');
    // batch 6 (v1.8.0)
    // search: auto-generated mixes (333)
    out.push('html[data-ytl-nomix][data-ytl-page="search"] [data-ytl-searchmix]{display:none!important}');
    // long press feedback (368): a light wash on the card while the finger is held
    out.push('[data-ytl-press]{background-color:rgba(128,128,128,.16)!important;border-radius:12px;transition:background-color .12s}');
    out.push('html[data-ytl-lowmotion] [data-ytl-press]{transition:none}');
    // moon button only with a video or a timer (363)
    out.push('#ytl-tools[data-ytl-off]{display:none!important}');
    // one-tap sleep mode (340): no comments under the player (related videos stay: the same-channel rule reads them)
    rule(RULES.sleepHide.map(function (x) { return 'html[data-ytl-sleepmode][data-ytl-page="watch"] ' + x; }), 'display:none!important');
    // empty feed note (326)
    out.push('#ytl-empty{margin:12px;padding:12px 14px;border-radius:12px;background:rgba(128,128,128,.14);font:14px/20px system-ui,sans-serif;' +
      'color:inherit;display:flex;gap:12px;align-items:center}#ytl-empty button{margin-left:auto;background:none;border:0;' +
      'color:var(--ytl-accent-text);font:500 14px system-ui,sans-serif;padding:8px 4px;white-space:nowrap}');
    return out.join('\n');
  }
  var CSS = sheet();

