import { describe, expect, it } from 'vitest';
import { mbpsToBps, parseMbps } from './format';

describe('parseMbps', () => {
  it.each([
    ['100', 100_000_000],
    ['20,5', 20_500_000],
    ['20.5', 20_500_000],
    ['', 0],
  ])('accepts %s', (input, bps) => {
    expect(parseMbps(input)).toEqual({ valid: true, bps });
  });

  it.each(['abc', '10O', '--20', '12..5'])('rejects %s without treating it as unlimited', (input) => {
    expect(parseMbps(input)).toMatchObject({ valid: false });
    expect(mbpsToBps(input)).toBeNull();
  });
});
