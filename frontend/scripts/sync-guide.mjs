// Copies the canonical setup guide (docs/SETUP_GUIDE.md) into the app so it can be shown at /guide.
// The copy is committed, so Docker builds (which only see ./frontend) still have it; a unit test fails if it drifts.
import { copyFileSync, existsSync, mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const source = resolve(here, '../../docs/SETUP_GUIDE.md');
const target = resolve(here, '../src/content/setup-guide.md');
if (existsSync(source)) {
  mkdirSync(dirname(target), { recursive: true });
  copyFileSync(source, target);
  console.log('setup guide synced from docs/SETUP_GUIDE.md');
} else if (!existsSync(target)) {
  console.error('setup guide missing: neither docs/SETUP_GUIDE.md nor src/content/setup-guide.md exists');
  process.exit(1);
}
