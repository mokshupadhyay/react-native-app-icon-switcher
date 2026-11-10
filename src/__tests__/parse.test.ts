import { normalizeIconName, parseChangeEvent, parseResult, parseState, toTimestamp } from '../parse';

describe('normalizeIconName', () => {
  it('maps primary spellings to null', () => {
    expect(normalizeIconName(null)).toBeNull();
    expect(normalizeIconName(undefined)).toBeNull();
    expect(normalizeIconName('')).toBeNull();
    expect(normalizeIconName('  ')).toBeNull();
    expect(normalizeIconName('Default')).toBeNull();
    expect(normalizeIconName('DEFAULT')).toBeNull();
  });

  it('trims alternate names', () => {
    expect(normalizeIconName(' Holiday ')).toBe('Holiday');
  });
});

describe('toTimestamp', () => {
  it('accepts dates, numbers and ISO strings', () => {
    expect(toTimestamp(new Date(1000))).toBe(1000);
    expect(toTimestamp(2000.4)).toBe(2000);
    expect(toTimestamp('1970-01-01T00:00:03.000Z')).toBe(3000);
  });

  it('rejects invalid times', () => {
    expect(() => toTimestamp('not a date')).toThrow(TypeError);
    expect(() => toTimestamp(NaN)).toThrow(TypeError);
  });
});

describe('parseState', () => {
  it('parses a native state object', () => {
    expect(
      parseState({
        supported: true,
        current: 'Dark',
        icons: ['Dark', 'Holiday', 3],
        hasPending: true,
        pending: null,
        schedule: [
          { icon: 'Holiday', at: 20 },
          { icon: null, at: 10 },
          { icon: 'Bad' },
        ],
        issues: ['x'],
      }),
    ).toEqual({
      supported: true,
      current: 'Dark',
      icons: ['Dark', 'Holiday'],
      pending: { icon: null },
      schedule: [
        { icon: null, at: 10 },
        { icon: 'Holiday', at: 20 },
      ],
      issues: ['x'],
    });
  });

  it('treats garbage as unsupported', () => {
    expect(parseState(null)).toEqual({
      supported: false,
      current: null,
      icons: [],
      pending: null,
      schedule: [],
      issues: [],
    });
  });
});

describe('parseResult', () => {
  it('keeps known statuses and the native icon', () => {
    expect(parseResult({ status: 'applied', icon: 'Dark' }, 'dark')).toEqual({ status: 'applied', icon: 'Dark' });
    expect(parseResult({ status: 'unchanged', icon: null }, 'Dark')).toEqual({ status: 'unchanged', icon: null });
  });

  it('falls back to pending and the requested icon', () => {
    expect(parseResult({ status: 'weird' }, 'Dark')).toEqual({ status: 'pending', icon: 'Dark' });
  });
});

describe('parseChangeEvent', () => {
  it('parses icon and previous', () => {
    expect(parseChangeEvent({ icon: 'Dark', previous: null })).toEqual({ icon: 'Dark', previous: null });
  });
});
