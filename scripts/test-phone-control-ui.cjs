#!/usr/bin/env node
// Browser-only fixtures: validates the shipped page, not upstream account APIs or TV playback.
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const assert = require('node:assert/strict');
const { chromium } = require('playwright');
const root = path.resolve(__dirname, '..');
const source = fs.readFileSync(path.join(root, 'kotlin-tv/core/src/main/kotlin/com/hongguotv/core/PhoneControlPage.kt'), 'utf8');
const html = source.split('"""')[1].replaceAll('$nonce', 'fixture');
const calls = []; let listReads = 0; let reserved = false;
const fixture = (body) => {
  calls.push(body);
  if (body.action === 'status') return { active: true, title: '浏览器测试剧目', episode: 3, episodes: 30, position: 42000, duration: 120000, playing: true, account: '测试账户', speed: 1, quality: 1080, reservation: reserved ? { id: '9', label: '已预约' } : null };
  if (body.action === 'search') return { items: [{ id: String(body.page), title: body.page === 1 ? '<script>window.injected=true</script>' : '第二页测试剧', badge: '共 30 集', playable: true, scope: 7 }], hasMore: body.page === 1 };
  if (body.action === 'related') return { items: [{ id: '9', title: '即将上线的第二季', playable: false, reservable: true, scope: 7 }], hasMore: false };
  if (body.action === 'list') {
    if (body.refresh) listReads = 0;
    listReads++;
    return { items: listReads === 1 ? [] : [{ id: '8', title: body.kind === 'upcoming' ? '未上线预约' : '账户片单剧', playable: body.kind !== 'upcoming', scope: 7 }], loading: listReads === 1, hasMore: false, message: listReads === 1 ? '正在读取…' : '读取完成' };
  }
  if (body.action === 'reserve') reserved = true;
  return { message: body.action === 'reserve' ? '正在预约…' : '已执行' };
};
const server = http.createServer((req, res) => {
  if (req.method === 'GET') { res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Referrer-Policy': 'no-referrer', 'Content-Security-Policy': "default-src 'none'; style-src 'unsafe-inline'; script-src 'nonce-fixture'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'" }); res.end(html); return; }
  let body = ''; req.on('data', c => body += c); req.on('end', () => {
    assert.equal(req.headers.origin, `http://127.0.0.1:${server.address().port}`);
    assert.equal(req.headers['x-control-request'], '1');
    const data = JSON.parse(body);
    // Slow search demonstrates that playback controls remain available while browsing.
    setTimeout(() => { res.writeHead(200, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(fixture(data))); }, data.action === 'search' ? 300 : 0);
  });
});
(async () => {
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  let browser;
  try {
    browser = await chromium.launch({ headless: true });
    const page = await browser.newPage({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 1 });
    const errors = []; page.on('pageerror', e => errors.push(String(e)));
    await page.goto(`http://127.0.0.1:${server.address().port}/fixture`);
    await page.waitForFunction(() => document.getElementById('playing').textContent === '浏览器测试剧目');
    await page.locator('#query').fill('测试'); await page.locator('#search').click();
    await page.locator('[data-cmd=pause]').click();
    await page.waitForFunction(() => document.querySelector('#rows h3'));
    assert(calls.some(c => c.action === 'pause'));
    assert.equal(await page.evaluate(() => window.injected), undefined);
    await page.locator('#next').click();
    await page.waitForFunction(() => document.querySelector('#rows h3').textContent === '第二页测试剧');
    await page.getByRole('button', { name: '电视播放 / 续播', exact: true }).click();
    await page.waitForTimeout(80);
    assert(calls.some(c => c.action === 'play' && c.id === '2' && c.scope === 7));
    await page.getByRole('button', { name: '相关作品 / 预约', exact: true }).click();
    await page.waitForFunction(() => document.querySelector('#rows h3').textContent === '即将上线的第二季');
    assert.equal(await page.getByRole('button', { name: '电视播放 / 续播', exact: true }).count(), 0);
    await page.getByRole('button', { name: '预约', exact: true }).click(); await page.waitForTimeout(80);
    assert(calls.some(c => c.action === 'reserve' && c.id === '9' && c.scope === 7));
    await page.getByRole('button', { name: '已预约', exact: true }).waitFor();
    assert(await page.getByRole('button', { name: '已预约', exact: true }).isDisabled());
    await page.getByRole('button', { name: '账户历史', exact: true }).click();
    await page.waitForFunction(() => document.querySelector('#rows h3')?.textContent === '账户片单剧');
    await page.getByRole('button', { name: '电视播放 / 续播', exact: true }).click(); await page.waitForTimeout(80);
    assert(calls.some(c => c.action === 'play' && c.source === 'history'));
    await page.locator('#speed').selectOption('2'); await page.waitForTimeout(80);
    await page.locator('#quality').selectOption('720'); await page.waitForTimeout(80);
    await page.locator('#episode').fill('8'); await page.locator('#jump').click(); await page.waitForTimeout(80);
    await page.locator('[data-key=left]').click(); await page.waitForTimeout(80);
    for (const [action, value] of [['speed', 2], ['quality', 720], ['episode', 8], ['key', 'left']]) assert(calls.some(c => c.action === action && c.value === value));
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
    fs.mkdirSync(path.join(root, 'outputs/phone-control'), { recursive: true });
    await page.screenshot({ path: path.join(root, 'outputs/phone-control/mobile.png'), fullPage: true });
    // Simulate visibility lifecycle and verify no background polling.
    await page.evaluate(() => { Object.defineProperty(document, 'hidden', { configurable: true, get: () => true }); document.dispatchEvent(new Event('visibilitychange')); });
    await page.waitForTimeout(100);
    const before = calls.filter(c => c.action === 'status').length;
    await page.waitForTimeout(3300);
    assert.equal(calls.filter(c => c.action === 'status').length, before);
    assert.deepEqual(errors, []);
    console.log(JSON.stringify({ passed: true, checks: ['search pagination', 'controls during search', 'XSS text rendering', 'play command and account scope', 'upcoming reservation', 'asynchronous list refresh', 'cloud history source', 'speed quality episode direction', 'mobile overflow', 'hidden-page polling stops'], requests: calls.length }, null, 2));
  } finally { if (browser) await browser.close(); server.close(); }
})().catch(e => { console.error(e); process.exitCode = 1; });
