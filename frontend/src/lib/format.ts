import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';

dayjs.extend(relativeTime);

const inr = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 2 });
const inrShort = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', notation: 'compact', maximumFractionDigits: 1 });
const num = new Intl.NumberFormat('en-IN');

export const money = (v: number | null | undefined) => (v == null ? '—' : inr.format(v));
export const moneyShort = (v: number | null | undefined) => (v == null ? '—' : inrShort.format(v));
export const number = (v: number | null | undefined) => (v == null ? '—' : num.format(v));
export const pct = (v: number | null | undefined, digits = 1) => (v == null ? '—' : `${(v * 100).toFixed(digits)}%`);
export const date = (v: string | null | undefined) => (v ? dayjs(v).format('DD MMM YYYY') : '—');
export const dateTime = (v: string | null | undefined) => (v ? dayjs(v).format('DD MMM YYYY, HH:mm') : '—');
export const time = (v: string | null | undefined) => (v ? dayjs(v).format('HH:mm') : '—');
export const fromNow = (v: string | null | undefined) => (v ? dayjs(v).fromNow() : '—');
export const today = () => dayjs().format('YYYY-MM-DD');

const ACRONYMS = new Set(['rfid', 'qr', 'upi', 'emi', 'sms', 'gst', 'pt', 'hiit', 'otp', 'id']);

/** "PENDING_PAYMENT" -> "Pending payment"; acronyms stay upper case ("RFID_CARD" -> "RFID card") */
export const label = (v: string | null | undefined) => {
  if (!v) return '—';
  const words = v.toLowerCase().split('_').map((w) => (ACRONYMS.has(w) ? w.toUpperCase() : w));
  const text = words.join(' ');
  return text.charAt(0).toUpperCase() + text.slice(1);
};

export const WEEKDAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
