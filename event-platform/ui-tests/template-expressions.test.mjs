import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

test('all page interpolations compile as JavaScript, including the organizer summary', async () => {
  const html = await readFile(new URL('../src/main/resources/static/index.html', import.meta.url), 'utf8');
  const expressions = [...html.matchAll(/\{\{([\s\S]*?)\}\}/g)];
  assert.ok(expressions.length > 10, 'Expected the complete booking page template');
  for (const [, expression] of expressions) {
    assert.doesNotThrow(() => new Function(`return (${expression})`), `Invalid template expression: ${expression}`);
  }
});
