import { readFileSync } from 'node:fs';
import { test as base, expect } from '@playwright/test';

const template = JSON.parse(readFileSync(new URL('../fixtures/template.json', import.meta.url)));
const instance = JSON.parse(readFileSync(new URL('../fixtures/instance.json', import.meta.url)));
const test = base.extend({
  diagnostics: [async ({ context, browser }, use, info) => {
    const errors = [];
    context.on('page', page => page.on('pageerror', error => errors.push(error.message)));
    await info.attach('browser-runtime', { body: JSON.stringify({ engine: info.project.name, version: browser.version() }), contentType: 'application/json' });
    await use();
    expect(errors, 'uncaught browser errors').toEqual([]);
  }, { auto: true }],
});
const cee = page => page.locator('cedar-embeddable-editor');
const notes = page => cee(page).getByRole('textbox', { name: 'Notes', exact: true });
const done = page => page.getByRole('button', { name: 'Done — return to chat', exact: true });

async function call(request, name, args) {
  const response = await request.post(process.env.CEE_MCP_TEST_RPC, { data: { name, arguments: args } });
  expect(response.ok(), await response.text()).toBe(true);
  const result = await response.json();
  expect(result.isError, JSON.stringify(result)).not.toBe(true);
  return result.content.filter(item => item.type === 'text').map(item => item.text).join('\n');
}

async function session(request, tool = 'fill_instance', extra = {}) {
  const text = await call(request, tool, { template: JSON.stringify(template),
    ...(tool === 'fill_instance' ? { timeout_seconds: 1 } : {}), ...extra });
  const url = text.match(/http:\/\/[^\s)]+\/s\/[0-9a-f-]+/)?.[0];
  expect(url, text).toBeTruthy();
  return { url, id: new URL(url).pathname.split('/')[2] };
}

async function open(page, target) {
  // Keep the real packaged page, bundle, data and submission routes. No production service is used.
  const origin = new URL(target.url).origin;
  await page.route('**/*', route => new URL(route.request().url()).origin === origin
    ? route.fallback() : route.abort('blockedbyclient'));
  await page.goto(target.url);
}

async function collected(request, target) {
  const text = await call(request, 'collect_instance', { session_id: target.id });
  expect(text).toContain('JSON-LD exactly as the editor produced it');
  return JSON.parse(text.slice(text.indexOf('{')));
}

async function submit(page) {
  const response = page.waitForResponse(response => response.url().endsWith('/submit') && response.request().method() === 'POST');
  await done(page).click();
  expect((await response).status()).toBe(204);
  await expect(page.locator('#status')).toContainText('Metadata sent back to the chat');
  await expect(done(page)).toBeEnabled();
}

async function readOnly(page) {
  await expect(cee(page).getByText('Notes', { exact: true }).first()).toBeVisible();
  await expect(done(page)).toBeHidden();
  const editable = await cee(page).getByRole('textbox').evaluateAll(elements =>
    elements.filter(element => !element.disabled && !element.readOnly).map(element => element.outerHTML));
  expect(editable, 'read-only sessions expose no editable text control').toEqual([]);
}

test('show_template renders the packaged editor without a submission control', async ({ page, request }) => {
  await open(page, await session(request, 'show_template'));
  await readOnly(page);
  await expect(page).toHaveTitle('Browser session template');
  await expect(page.locator('#status')).toHaveText('Read-only view of a template.');
});

test('show_instance displays the supplied value read only', async ({ page, request }) => {
  await open(page, await session(request, 'show_instance', { instance: JSON.stringify(instance) }));
  await readOnly(page);
  await expect.poll(() => cee(page).evaluate(el => el.currentMetadata?.Notes?.['@value'])).toBe('Read only answer');
  await expect(page.locator('#status')).toHaveText('Read-only view of a metadata instance.');
});

test('fill, Done and collect preserve exact metadata and a later submission replaces it', async ({ page, request }) => {
  const target = await session(request);
  await open(page, target);
  await expect(notes(page)).toBeEditable();
  for (const value of ['First browser answer', 'Replacement browser answer']) {
    await notes(page).fill(value);
    const expected = await cee(page).evaluate(el => el.currentMetadata);
    expect(expected.Notes['@value']).toBe(value);
    await submit(page);
    expect(await collected(request, target)).toEqual(expected);
  }
});

test('a refused submission retains input and the existing Done button can retry', async ({ page, request }) => {
  const target = await session(request);
  await open(page, target);
  await notes(page).fill('Retained after refusal');
  await page.route('**/submit', route => route.fulfill({ status: 503, body: 'Temporarily unavailable' }));
  await done(page).click();
  await expect(page.locator('#status')).toHaveText('Submit failed: HTTP 503 Temporarily unavailable');
  await expect(done(page)).toBeEnabled();
  await expect(notes(page)).toHaveValue('Retained after refusal');
  expect(await call(request, 'collect_instance', { session_id: target.id })).toMatch(/not.*(?:submitted|pressed Done)/i);
  await page.unroute('**/submit');
  await submit(page);
  expect((await collected(request, target)).Notes['@value']).toBe('Retained after refusal');
});

test('simultaneous session pages keep their values and collected results separate', async ({ page, context, request }) => {
  const [first, second] = await Promise.all([session(request), session(request)]);
  expect(first.id).not.toBe(second.id);
  const other = await context.newPage();
  await Promise.all([open(page, first), open(other, second)]);
  await notes(page).fill('First session');
  await notes(other).fill('Second session');
  await submit(page);
  expect(await call(request, 'collect_instance', { session_id: second.id })).toMatch(/not.*(?:submitted|pressed Done)/i);
  await submit(other);
  expect((await collected(request, first)).Notes['@value']).toBe('First session');
  expect((await collected(request, second)).Notes['@value']).toBe('Second session');
});

for (const [path, message] of [
  ['**/cee/cedar-embeddable-editor.js', 'Could not load the CEE bundle'],
  ['**/data', 'Could not fetch session data: HTTP 503'],
]) {
  test(`startup failure at ${path} explains the failure and offers no premature Done`, async ({ page, request }) => {
    const target = await session(request);
    await page.route(path, route => route.fulfill({ status: 503, body: 'Temporarily unavailable' }));
    await open(page, target);
    await expect(page.locator('#status')).toContainText(message);
    await expect(done(page)).toBeHidden();
    await page.unroute(path);
    await page.reload();
    await expect(notes(page)).toBeEditable();
    await expect(done(page)).toBeVisible();
  });
}
