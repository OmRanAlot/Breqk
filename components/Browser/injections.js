// CSS and JS injected into Instagram and YouTube WebViews
// to remove Reels, Shorts, and algorithmic feeds.
//
// IMPORTANT: Instagram and YouTube periodically change their CSS class names.
// Prefer targeting stable attributes (href, aria-label, element type) over
// obfuscated class names. If selectors stop working, inspect instagram.com
// in a desktop browser and update the selectors here.

export const INSTAGRAM_CSS = `
  /* Remove Reels tab from bottom navigation */
  a[href="/reels/"],
  a[href*="/reels"] { display: none !important; }

  /* Remove Reels from home feed (video posts) */
  article[role="presentation"] video { display: none !important; }

  /* Remove Explore / Search page link */
  a[href="/explore/"] { display: none !important; }

  /* Remove suggested posts section */
  ._aa4w,
  [data-visualcompletion="ignore-dynamic"] { display: none !important; }

  /* Remove Stories bar on home feed */
  ._acuy { display: none !important; }
`;

export const YOUTUBE_CSS = `
  /* Remove Shorts shelf on homepage */
  ytd-reel-shelf-renderer { display: none !important; }

  /* Remove Shorts in sidebar navigation */
  a[href^="/shorts"],
  ytd-guide-entry-renderer a[href="/shorts"] { display: none !important; }

  /* Remove homepage recommendations entirely — subscriptions feed remains accessible */
  ytd-browse[page-subtype="home"] ytd-rich-grid-renderer { display: none !important; }

  /* Remove autoplay next video overlay */
  .ytp-autonav-endscreen-upnext-button { display: none !important; }

  /* Remove end screen suggested videos */
  .ytp-endscreen-content { display: none !important; }
`;

// Wraps CSS in a MutationObserver so rules re-apply as the DOM updates
// (both Instagram and YouTube are heavy SPAs that load content dynamically)
export const buildInjectionScript = (css) => `
  (function() {
    const styleId = 'doom-blocker-styles';

    function injectStyles() {
      let existing = document.getElementById(styleId);
      if (!existing) {
        const style = document.createElement('style');
        style.id = styleId;
        style.textContent = \`${css}\`;
        (document.head || document.documentElement).appendChild(style);
      }
    }

    injectStyles();

    const observer = new MutationObserver(() => injectStyles());
    observer.observe(document.documentElement, {
      childList: true,
      subtree: true
    });
  })();
  true;
`;
