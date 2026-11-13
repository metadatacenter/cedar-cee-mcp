// One packaged JVM serves all browser workers. The loopback broker multiplexes real MCP stdio
// requests; it never implements tools or session routes itself. Every test creates fresh sessions.
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { createInterface } from 'node:readline';
import { once } from 'node:events';
import { createWriteStream } from 'node:fs';
import { mkdir, mkdtemp, writeFile, rm, stat, readdir } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';

async function newest(directory) {
  const entries = await readdir(directory, { withFileTypes: true });
  return Math.max(0, ...await Promise.all(entries.map(entry => entry.isDirectory()
    ? newest(join(directory, entry.name)) : stat(join(directory, entry.name)).then(value => value.mtimeMs))));
}

export default async function setup() {
  const root = fileURLToPath(new URL('..', import.meta.url));
  const jar = resolve(root, 'target/cedar-cee-mcp.jar');
  const jarTime = (await stat(jar).catch(() => { throw new Error('Run mvn verify before the browser gate'); })).mtimeMs;
  assert.ok(jarTime >= Math.max(await newest(join(root, 'src/main')), (await stat(join(root, 'pom.xml'))).mtimeMs),
    'Packaged MCP server is older than its sources; run mvn verify');
  const temporary = await mkdtemp(join(tmpdir(), 'cee-browser-'));
  // The same no-op browser opener boundary as EndToEndStdioIT, without touching system settings.
  for (const name of ['open', 'xdg-open'])
    await writeFile(join(temporary, name), '#!/bin/sh\nexit 0\n', { mode: 0o755 });
  const reports = resolve(process.env.CEE_MCP_BROWSER_REPORT_DIR ?? 'reports');
  await mkdir(reports, { recursive: true });
  const log = createWriteStream(join(reports, 'server.log'));
  const java = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin/java') : '/usr/bin/java';
  const child = spawn(java, ['-Djava.awt.headless=true', '-jar', jar], {
    env: { ...process.env, PATH: temporary }, stdio: ['pipe', 'pipe', 'pipe'],
  });
  child.stderr.pipe(log);
  const exited = new Promise(resolve => child.once('close', resolve));
  const pending = new Map();
  let nextId = 0;
  const rejectAll = error => { for (const request of pending.values()) request.reject(error); pending.clear(); };
  child.on('error', rejectAll);
  child.stdin.on('error', rejectAll);
  child.on('exit', code => rejectAll(new Error(`MCP server exited ${code}; see ${reports}/server.log`)));
  const lines = createInterface({ input: child.stdout });
  lines.on('line', line => {
    try {
      const message = JSON.parse(line), request = pending.get(message.id);
      if (request) { pending.delete(message.id); message.error ? request.reject(new Error(JSON.stringify(message.error))) : request.resolve(message.result); }
    } catch (error) { rejectAll(error); }
  });
  const request = (method, params) => new Promise((resolve, reject) => {
    const id = ++nextId;
    const timer = setTimeout(() => { pending.delete(id); reject(new Error(`MCP ${method} timed out`)); }, 20_000);
    pending.set(id, { resolve: result => { clearTimeout(timer); resolve(result); }, reject: error => { clearTimeout(timer); reject(error); } });
    child.stdin.write(JSON.stringify({ jsonrpc: '2.0', id, method, params }) + '\n');
  });
  const broker = createServer(async (incoming, response) => {
    try {
      assert.equal(incoming.method, 'POST');
      assert.equal(incoming.url, '/call');
      const chunks = [];
      for await (const chunk of incoming) chunks.push(chunk);
      const params = JSON.parse(Buffer.concat(chunks).toString());
      const result = await request('tools/call', params);
      response.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify(result));
    } catch (error) { response.writeHead(500).end(String(error)); }
  });
  const close = async () => {
    broker.closeAllConnections();
    if (broker.listening) await new Promise(resolve => broker.close(resolve));
    child.kill('SIGTERM');
    const timer = setTimeout(() => child.kill('SIGKILL'), 2000);
    await exited;
    clearTimeout(timer);
    lines.close();
    log.end();
    await rm(temporary, { recursive: true, force: true });
  };
  try {
    const initialized = await request('initialize', { protocolVersion: '2024-11-05', capabilities: {},
      clientInfo: { name: 'browser-gate', version: '1' } });
    assert.equal(initialized.serverInfo.name, 'cedar-cee-mcp');
    child.stdin.write(JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }) + '\n');
    broker.listen(0, '127.0.0.1');
    await once(broker, 'listening');
    process.env.CEE_MCP_TEST_RPC = `http://127.0.0.1:${broker.address().port}/call`;
    return close;
  } catch (error) { await close(); throw error; }
}
