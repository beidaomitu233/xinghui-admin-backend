import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
if (process.argv.includes('--self-test')) {
  for (const status of ['success', 'failure', 'cancelled', 'skipped', 'unknown']) {
    const result = spawnSync(process.execPath, [import.meta.filename], {
      env: { ...process.env, REQUIRED_JOBS: 'build,services', NEEDS_JSON: JSON.stringify({ build: { result: 'success' }, services: { result: status } }) },
      stdio: 'pipe',
    });
    assert.equal(result.status, status === 'success' ? 0 : 1);
  }
  const missing = spawnSync(process.execPath, [import.meta.filename], { env: { ...process.env, REQUIRED_JOBS: 'build,services', NEEDS_JSON: '{}' }, stdio: 'pipe' });
  assert.equal(missing.status, 1);
  console.log('Controlled child failures/cancelled/skipped/missing rejected with exit 1');
} else {
  try {
    const required = (process.env.REQUIRED_JOBS ?? '').split(',').filter(Boolean);
    const needs = JSON.parse(process.env.NEEDS_JSON ?? 'null');
    assert.ok(required.length > 0 && needs && Object.keys(needs).length === required.length);
    assert.ok(required.every((job) => needs[job]?.result === 'success'));
    console.log('Every required dependency succeeded');
  } catch {
    console.error('Quality gate rejected missing, failed, cancelled, or skipped dependency');
    process.exitCode = 1;
  }
}
