import { Alert, Checkbox, Group, NumberInput, Stack, Switch, Text, TextInput } from '@mantine/core';
import { useState } from 'react';
import { PRICING, type PricingSettings } from '@/api/pricing';
import { useAuth } from '@/auth/AuthContext';
import { hasAnyRole, MANAGERS, ORG_WIDE } from '@/auth/roles';
import { money } from '@/lib/format';
import { useApiQuery } from '@/lib/useApi';

/** State of the optional negotiated (custom) price at the point of sale. Managers and admins only. */
export function useNegotiatedPrice(isNewSale: boolean) {
  const { user } = useAuth();
  const allowed = isNewSale && hasAnyRole(user, MANAGERS);
  const unlimited = hasAnyRole(user, ORG_WIDE);
  const settings = useApiQuery<PricingSettings>(allowed ? PRICING.settings : null);
  const [active, setActive] = useState(false);
  const [price, setPrice] = useState<number | string>('');
  const [reason, setReason] = useState('');
  const [keep, setKeep] = useState(false);
  const maxPct = settings.data?.maxOverrideDiscountPct ?? 50;

  const discountPct = (list: number | null) =>
    list && price !== '' ? Math.round(((list - Number(price)) / list) * 10000) / 100 : null;

  /** Problem with the entered values for this list price, or null. */
  const problem = (list: number | null): string | null => {
    if (!active) return null;
    if (price === '' || Number(price) < 0) return 'Enter the negotiated price';
    if (list != null && Number(price) >= list) return `Must be below the list price of ${money(list)}`;
    const pct = discountPct(list);
    if (!unlimited && pct != null && pct > maxPct) return `${pct}% is more than the ${maxPct}% a manager may give; ask a gym admin`;
    if (reason.trim().length < 5) return 'A reason is required (at least 5 characters)';
    return null;
  };

  return {
    allowed, active, setActive, price, setPrice, reason, setReason, keep, setKeep, maxPct, unlimited, discountPct, problem,
    valid: (list: number | null) => problem(list) === null,
    body: () => (allowed && active
      ? { customPrice: Number(price), customPriceReason: reason.trim(), keepCustomPriceOnRenewal: keep }
      : {}),
    reset: () => { setActive(false); setPrice(''); setReason(''); setKeep(false); },
  };
}

export function NegotiatedPriceFields({ state: s, listPrice }: {
  state: ReturnType<typeof useNegotiatedPrice>;
  listPrice: number | null;
}) {
  const pct = s.discountPct(listPrice);
  const problem = s.problem(listPrice);
  return (
    <Stack gap="xs">
      <Switch label="Sell at a negotiated price (corporate / special rate)" checked={s.active}
        onChange={(e) => s.setActive(e.currentTarget.checked)} />
      {s.active && (
        <>
          <Group grow align="flex-start">
            <NumberInput label="Negotiated price (₹, excl. GST)" min={0} decimalScale={2} thousandSeparator=","
              value={s.price} onChange={s.setPrice}
              description={listPrice != null ? `List price ${money(listPrice)}${pct != null ? ` · ${pct}% off` : ''}` : 'Pick a plan'} />
            <TextInput label="Reason" placeholder="e.g. Acme Corp tie-up" value={s.reason} maxLength={300}
              onChange={(e) => s.setReason(e.currentTarget.value)} withAsterisk />
          </Group>
          <Checkbox label="Keep this price on renewals (never above the list price)" checked={s.keep}
            onChange={(e) => s.setKeep(e.currentTarget.checked)} />
          <Text size="xs" c="dimmed">
            The invoice shows the list price and a separate “Negotiated price adjustment” line; GST is charged on the
            negotiated price. Discount codes cannot be combined.
            {!s.unlimited && ` Managers may give up to ${s.maxPct}% off.`}
          </Text>
          {problem && <Alert color="orange" p="xs">{problem}</Alert>}
        </>
      )}
    </Stack>
  );
}
