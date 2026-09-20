/* URL Shortener — front end.
 *
 * Plain ES modules-free JavaScript on purpose: the whole point of this page is
 * that it ships with the jar and needs no build step, no node_modules, and no
 * CDN at runtime.
 */
(function () {
    'use strict';

    const API = '/api/v1/links';
    const TIMELINE_DAYS = 14;
    const BAR_MAX_THICKNESS = 24;

    let selectedSlug = null;

    /* ── helpers ─────────────────────────────────────────────── */

    /** Every string from the API originates as user input, so nothing is
     *  interpolated into markup without escaping first. */
    function esc(value) {
        return String(value == null ? '' : value)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    function compact(n) {
        if (n < 1000) return String(n);
        if (n < 1000000) return (n / 1000).toFixed(n < 10000 ? 1 : 0).replace(/\.0$/, '') + 'K';
        return (n / 1000000).toFixed(1).replace(/\.0$/, '') + 'M';
    }

    function shortDate(iso) {
        const d = new Date(iso);
        return d.toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
    }

    function el(id) { return document.getElementById(id); }

    let toastTimer;
    function toast(message) {
        const t = el('toast');
        t.textContent = message;
        t.hidden = false;
        clearTimeout(toastTimer);
        toastTimer = setTimeout(() => { t.hidden = true; }, 2200);
    }

    async function api(path, options) {
        const res = await fetch(path, options);
        const text = await res.text();
        let body = null;
        if (text) {
            try { body = JSON.parse(text); } catch (e) { body = null; }
        }
        if (!res.ok) {
            const message = body && body.message ? body.message : 'Request failed (' + res.status + ')';
            throw new Error(message);
        }
        return body;
    }

    /* ── theme ───────────────────────────────────────────────── */

    function systemPrefersDark() {
        return window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches;
    }

    function currentlyDark() {
        const stamped = document.documentElement.getAttribute('data-theme');
        return stamped ? stamped === 'dark' : systemPrefersDark();
    }

    function applyTheme(theme) {
        document.documentElement.setAttribute('data-theme', theme);
        document.querySelector('[data-theme-label]').textContent = theme === 'dark' ? 'Light' : 'Dark';
        // Best-effort only: storage throws in private mode and comes back empty
        // when site data is cleared, so the page must render fine without it.
        try { localStorage.setItem('theme', theme); } catch (e) { /* ignore */ }
    }

    function initTheme() {
        let saved = null;
        try { saved = localStorage.getItem('theme'); } catch (e) { /* ignore */ }
        applyTheme(saved || (systemPrefersDark() ? 'dark' : 'light'));

        el('themeToggle').addEventListener('click', function () {
            applyTheme(currentlyDark() ? 'light' : 'dark');
            if (selectedSlug) loadAnalytics(selectedSlug);
        });
    }

    /* ── create ──────────────────────────────────────────────── */

    function initForm() {
        el('createForm').addEventListener('submit', async function (event) {
            event.preventDefault();

            const url = el('url').value.trim();
            const alias = el('alias').value.trim();
            const ttl = el('ttl').value;
            const errorEl = el('formError');
            const button = el('submitBtn');

            errorEl.hidden = true;
            if (!url) {
                errorEl.textContent = 'Enter a URL to shorten.';
                errorEl.hidden = false;
                return;
            }

            const payload = { url: url };
            if (alias) payload.alias = alias;
            if (ttl) payload.ttlSeconds = Number(ttl);

            button.disabled = true;
            button.textContent = 'Shortening…';
            try {
                const link = await api(API, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(payload)
                });
                showResult(link);
                el('url').value = '';
                el('alias').value = '';
                await loadLinks();
            } catch (err) {
                errorEl.textContent = err.message;
                errorEl.hidden = false;
            } finally {
                button.disabled = false;
                button.textContent = 'Shorten';
            }
        });

        el('copyBtn').addEventListener('click', async function () {
            const text = el('resultLink').textContent;
            try {
                await navigator.clipboard.writeText(text);
                toast('Copied to clipboard');
            } catch (e) {
                toast('Copy failed — select the link instead');
            }
        });
    }

    function showResult(link) {
        el('resultLink').textContent = link.shortUrl;
        el('resultLink').href = link.shortUrl;
        el('resultDest').textContent = 'redirects to ' + link.longUrl;
        el('result').hidden = false;
    }

    /* ── links list ──────────────────────────────────────────── */

    async function loadLinks() {
        const body = el('linksBody');
        try {
            const links = await api(API);
            if (!links.length) {
                body.innerHTML = '<p class="empty">No links yet. Shorten one above.</p>';
                return;
            }

            const rows = links.map(function (l) {
                return '<tr>'
                    + '<td><a class="slug-cell" href="' + esc(l.shortUrl) + '" target="_blank" rel="noopener">/' + esc(l.slug) + '</a></td>'
                    + '<td class="dest-cell" title="' + esc(l.longUrl) + '">' + esc(l.longUrl) + '</td>'
                    + '<td class="num">' + esc(l.totalClicks) + '</td>'
                    + '<td>' + (l.active ? '<span class="pill">active</span>' : '<span class="pill off">disabled</span>') + '</td>'
                    + '<td><button type="button" class="ghost-btn" data-slug="' + esc(l.slug) + '">Analytics</button></td>'
                    + '</tr>';
            }).join('');

            body.innerHTML =
                '<table><thead><tr>'
                + '<th>Short</th><th>Destination</th><th>Clicks</th><th>Status</th><th></th>'
                + '</tr></thead><tbody>' + rows + '</tbody></table>';

            body.querySelectorAll('button[data-slug]').forEach(function (btn) {
                btn.addEventListener('click', function () { loadAnalytics(btn.dataset.slug); });
            });
        } catch (err) {
            body.innerHTML = '<p class="empty">Could not load links: ' + esc(err.message) + '</p>';
        }
    }

    /* ── analytics ───────────────────────────────────────────── */

    async function loadAnalytics(slug) {
        selectedSlug = slug;
        const card = el('analyticsCard');
        card.hidden = false;
        el('analyticsSlug').textContent = '/' + slug;

        try {
            const report = await api(API + '/' + encodeURIComponent(slug) + '/analytics');

            el('kpiRow').innerHTML =
                  kpi('Total clicks', compact(report.totalClicks), 'all time, including unflushed')
                + kpi('Unique visitors', compact(report.uniqueVisitors), 'by salted IP hash')
                + kpi('Clicks in range', compact(report.clicksInRange), 'last 30 days');

            renderTimeline(report.timeline);
            renderBreakdowns(report);

            card.scrollIntoView({ behavior: 'smooth', block: 'start' });
        } catch (err) {
            el('kpiRow').innerHTML = '<p class="empty">Could not load analytics: ' + esc(err.message) + '</p>';
            el('timeline').innerHTML = '';
            el('breakdowns').innerHTML = '';
        }
    }

    function kpi(label, value, note) {
        return '<div class="kpi">'
            + '<p class="kpi-label">' + esc(label) + '</p>'
            + '<p class="kpi-value">' + esc(value) + '</p>'
            + (note ? '<p class="kpi-note">' + esc(note) + '</p>' : '')
            + '</div>';
    }

    /** Fills gaps so the axis is a continuous run of days, not just the days
     *  that happened to have traffic. */
    function densify(timeline) {
        const byDate = {};
        (timeline || []).forEach(function (p) { byDate[p.date] = p.clicks; });

        const out = [];
        const today = new Date();
        for (let i = TIMELINE_DAYS - 1; i >= 0; i--) {
            const d = new Date(today);
            d.setDate(today.getDate() - i);
            const key = d.toISOString().slice(0, 10);
            out.push({ date: key, clicks: byDate[key] || 0 });
        }
        return out;
    }

    /**
     * Picks a clean tick *step* rather than a clean maximum.
     *
     * Choosing the maximum first is the obvious approach and it goes wrong:
     * a peak of 7 rounds up to a max of 10, which over 4 ticks gives 2.5 per
     * tick and an axis labelled 0, 3, 5, 8, 10. Deriving the step first and
     * letting max = step x ticks keeps every gridline on a whole number.
     */
    function niceScale(peak, tickCount) {
        const steps = [1, 2, 5, 10, 20, 25, 50, 100, 200, 250, 500, 1000];
        const needed = Math.max(peak, 1) / tickCount;

        let step = steps[steps.length - 1];
        for (let i = 0; i < steps.length; i++) {
            if (steps[i] >= needed) { step = steps[i]; break; }
        }
        // Past the table, fall back to powers of ten so the step stays round.
        while (step * tickCount < peak) step *= 10;

        return { step: step, max: step * tickCount };
    }

    /** Column with a 4px rounded cap and square feet at the baseline. */
    function barPath(x, y, w, h, r) {
        const radius = Math.min(r, w / 2, h);
        if (h <= 0) return '';
        return 'M' + x + ' ' + (y + h)
            + 'V' + (y + radius)
            + 'a' + radius + ' ' + radius + ' 0 0 1 ' + radius + ' ' + (-radius)
            + 'h' + (w - 2 * radius)
            + 'a' + radius + ' ' + radius + ' 0 0 1 ' + radius + ' ' + radius
            + 'V' + (y + h) + 'Z';
    }

    function renderTimeline(timeline) {
        const data = densify(timeline);
        const host = el('timeline');

        const W = 720, H = 200;
        const padL = 34, padR = 8, padT = 16, padB = 26;
        const plotW = W - padL - padR;
        const plotH = H - padT - padB;

        const peak = Math.max.apply(null, data.map(function (d) { return d.clicks; }));
        const ticks = 4;
        const scale = niceScale(peak, ticks);
        const max = scale.max;
        const band = plotW / data.length;
        const barW = Math.min(BAR_MAX_THICKNESS, band * 0.55);

        let svg = '<svg viewBox="0 0 ' + W + ' ' + H + '" role="img" '
            + 'aria-label="Clicks per day over the last ' + TIMELINE_DAYS + ' days">';

        // Gridlines + y ticks — solid hairlines, never dashed.
        for (let i = 0; i <= ticks; i++) {
            const value = scale.step * i;
            const y = padT + plotH - (value / max) * plotH;
            svg += '<line class="grid-line" x1="' + padL + '" y1="' + y + '" x2="' + (W - padR) + '" y2="' + y + '"/>';
            svg += '<text class="tick-text" x="' + (padL - 8) + '" y="' + (y + 3.5) + '" text-anchor="end">'
                + value.toLocaleString() + '</text>';
        }

        // Only the peak is direct-labelled; the axis carries the rest.
        const peakIndex = data.reduce(function (best, d, i) {
            return d.clicks > data[best].clicks ? i : best;
        }, 0);

        data.forEach(function (d, i) {
            const x = padL + i * band + (band - barW) / 2;
            const h = max === 0 ? 0 : (d.clicks / max) * plotH;
            const y = padT + plotH - h;

            if (h > 0) {
                svg += '<path class="bar" d="' + barPath(x, y, barW, h, 4) + '"><title>'
                    + esc(shortDate(d.date)) + ': ' + d.clicks + (d.clicks === 1 ? ' click' : ' clicks')
                    + '</title></path>';
            }

            // Generous hit target regardless of bar height, so hovering a
            // zero-height day still explains itself.
            svg += '<rect class="bar-hit" x="' + (padL + i * band) + '" y="' + padT
                + '" width="' + band + '" height="' + plotH + '"><title>'
                + esc(shortDate(d.date)) + ': ' + d.clicks + (d.clicks === 1 ? ' click' : ' clicks')
                + '</title></rect>';

            if (i === peakIndex && d.clicks > 0) {
                svg += '<text class="bar-label" x="' + (x + barW / 2) + '" y="' + (y - 6) + '">'
                    + d.clicks + '</text>';
            }

            // Label every third day so ticks never collide.
            if (i % 3 === 0 || i === data.length - 1) {
                svg += '<text class="tick-text" x="' + (padL + i * band + band / 2) + '" y="' + (H - 8)
                    + '" text-anchor="middle">' + esc(shortDate(d.date)) + '</text>';
            }
        });

        svg += '<line class="axis-line" x1="' + padL + '" y1="' + (padT + plotH)
            + '" x2="' + (W - padR) + '" y2="' + (padT + plotH) + '"/>';
        svg += '</svg>';

        host.innerHTML = svg;
        renderTimelineTable(data);
    }

    /** The table twin: every value stays reachable without relying on hover. */
    function renderTimelineTable(data) {
        const rows = data.map(function (d) {
            return '<tr><td>' + esc(shortDate(d.date)) + '</td><td class="num">' + d.clicks + '</td></tr>';
        }).join('');
        el('timelineTable').innerHTML =
            '<table><thead><tr><th>Day</th><th>Clicks</th></tr></thead><tbody>' + rows + '</tbody></table>';
    }

    function renderBreakdowns(report) {
        el('breakdowns').innerHTML = [
            breakdown('Devices', report.devices),
            breakdown('Browsers', report.browsers),
            breakdown('Referrers', report.topReferrers),
            breakdown('Countries', report.countries)
        ].join('');
    }

    function breakdown(title, buckets) {
        let inner;
        if (!buckets || !buckets.length) {
            inner = '<p class="empty">No data yet.</p>';
        } else {
            const max = Math.max.apply(null, buckets.map(function (b) { return b.count; }));
            inner = buckets.map(function (b) {
                const pct = max === 0 ? 0 : (b.count / max) * 100;
                return '<div class="brow">'
                    + '<div class="brow-head">'
                    + '<span class="brow-key" title="' + esc(b.key) + '">' + esc(b.key) + '</span>'
                    + '<span class="brow-val">' + esc(b.count) + '</span>'
                    + '</div>'
                    + '<div class="brow-track"><div class="brow-fill" style="width:' + pct.toFixed(1) + '%"></div></div>'
                    + '</div>';
            }).join('');
        }
        return '<section class="breakdown"><h3>' + esc(title) + '</h3>' + inner + '</section>';
    }

    /* ── cache ───────────────────────────────────────────────── */

    async function loadCache() {
        try {
            const s = await api(API + '/_cache-stats');
            const total = s.hits + s.negativeHits + s.misses;
            el('cacheRow').innerHTML =
                  kpi('Hit ratio', (s.hitRatio * 100).toFixed(1) + '%', total + ' lookups')
                + kpi('Hits', compact(s.hits), 'served from Redis')
                + kpi('Negative hits', compact(s.negativeHits), 'unknown slugs, no DB read')
                + kpi('Misses', compact(s.misses), 'fell through to MongoDB')
                + kpi('Errors', compact(s.errors), 'degraded to MongoDB');
        } catch (err) {
            el('cacheRow').innerHTML = '<p class="empty">Could not load cache stats.</p>';
        }
    }

    /* ── wire up ─────────────────────────────────────────────── */

    function init() {
        initTheme();
        initForm();

        el('refreshBtn').addEventListener('click', loadLinks);
        el('refreshCache').addEventListener('click', loadCache);
        el('closeAnalytics').addEventListener('click', function () {
            el('analyticsCard').hidden = true;
            selectedSlug = null;
        });

        el('toggleTable').addEventListener('click', function () {
            const table = el('timelineTable');
            const showing = !table.hidden;
            table.hidden = showing;
            this.setAttribute('aria-expanded', String(!showing));
            this.textContent = showing ? 'Show data table' : 'Hide data table';
        });

        loadLinks();
        loadCache();
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
