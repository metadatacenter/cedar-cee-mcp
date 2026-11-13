import { defineConfig } from '@playwright/test';
import { resolve } from 'node:path';
import { availableParallelism } from 'node:os';

const reports = resolve(process.env.CEE_MCP_BROWSER_REPORT_DIR ?? 'reports');
const localWorkers = availableParallelism() >= 16 ? 12 : Math.min(4, availableParallelism());
export default defineConfig({
  testDir: './tests',
  globalSetup: './server.mjs',
  fullyParallel: true,
  forbidOnly: true,
  retries: 0,
  workers: Number(process.env.CEDAR_TEST_WORKERS ?? (process.env.CI ? 2 : localWorkers)),
  timeout: 30_000,
  expect: { timeout: 15_000 },
  outputDir: resolve(reports, 'artifacts'),
  reporter: [['line'], ['json', { outputFile: resolve(reports, 'results.json') }]],
  use: { viewport: { width: 1400, height: 1000 }, trace: 'retain-on-failure', screenshot: 'only-on-failure' },
  projects: ['chromium', 'firefox', 'webkit'].map(browserName => ({ name: browserName, use: { browserName } })),
});
