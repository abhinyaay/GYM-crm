import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { resolveHref, slug } from './GuidePage';

describe('setup guide page', () => {
  it('builds stable heading ids', () => {
    expect(slug('5. Publishing the website (going live)')).toBe('5-publishing-the-website-going-live');
  });

  it('points repository links at GitHub and keeps external links', () => {
    expect(resolveHref('../scripts/gate_bridge.py')).toBe('https://github.com/abhinyaay/GYM-crm/blob/main/scripts/gate_bridge.py');
    expect(resolveHref('AWS_DEPLOYMENT.md')).toBe('https://github.com/abhinyaay/GYM-crm/blob/main/docs/AWS_DEPLOYMENT.md');
    expect(resolveHref('https://docker.com')).toBe('https://docker.com');
    expect(resolveHref('#backups')).toBe('#backups');
  });

  it('ships the same guide as docs/SETUP_GUIDE.md', () => {
    const canonical = resolve(__dirname, '../../../../docs/SETUP_GUIDE.md');
    if (!existsSync(canonical)) return;   // Docker build context: only the committed copy exists
    const copy = readFileSync(resolve(__dirname, '../../content/setup-guide.md'), 'utf8');
    expect(copy).toBe(readFileSync(canonical, 'utf8'));
  });
});
