import { describe, expect, it } from 'vitest';
import { label, money, pct } from '@/lib/format';

describe('formatting', () => {
  it('formats INR, percentages and enum labels', () => {
    expect(money(4130)).toContain('4,130');
    expect(money(null)).toBe('—');
    expect(pct(0.1234, 1)).toBe('12.3%');
    expect(label('PENDING_PAYMENT')).toBe('Pending payment');
  });
});
