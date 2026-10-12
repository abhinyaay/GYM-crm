import { ActionIcon, Button, Group, Tooltip } from '@mantine/core';
import { IconFileInvoice, IconReceiptRefund } from '@tabler/icons-react';
import { useState, type MouseEvent } from 'react';
import { creditNotePdfPath, downloadPdf, invoicePdfPath, type RefundWithCreditNote } from '@/api/pricing';
import { notifyError } from '@/lib/notify';
import { useApiQuery } from '@/lib/useApi';

type Doc = { kind: 'invoice'; invoiceId: number; number?: string } | { kind: 'creditNote'; refundId: number; number?: string | null };

/** Downloads a GST tax invoice or credit note PDF (authenticated fetch -> blob). */
export function TaxDocumentButton({ doc, compact = false, label }: { doc: Doc; compact?: boolean; label?: string }) {
  const [busy, setBusy] = useState(false);
  const isInvoice = doc.kind === 'invoice';
  const path = isInvoice ? invoicePdfPath(doc.invoiceId) : creditNotePdfPath(doc.refundId);
  const fallback = `${doc.number || (isInvoice ? `invoice-${doc.invoiceId}` : `credit-note-${doc.refundId}`)}.pdf`;
  const text = label ?? (isInvoice ? 'Tax invoice (PDF)' : 'Credit note (PDF)');
  const Icon = isInvoice ? IconFileInvoice : IconReceiptRefund;

  const run = async (e: MouseEvent) => {
    e.stopPropagation();
    setBusy(true);
    try {
      await downloadPdf(path, fallback);
    } catch (err) {
      notifyError(err, 'Download failed');
    } finally {
      setBusy(false);
    }
  };

  if (compact) {
    return (
      <Tooltip label={text}>
        <ActionIcon variant="subtle" loading={busy} onClick={run} aria-label={text}>
          <Icon size={16} />
        </ActionIcon>
      </Tooltip>
    );
  }
  return (
    <Button size="xs" variant="light" leftSection={<Icon size={14} />} loading={busy} onClick={run}>
      {text}
    </Button>
  );
}

/** Credit-note downloads for the refunds of one payment (loaded on demand). */
export function CreditNoteButtons({ paymentId }: { paymentId: number }) {
  const refunds = useApiQuery<RefundWithCreditNote[]>(`/api/v1/payments/${paymentId}/refunds`);
  const done = (refunds.data ?? []).filter((r) => r.status === 'SUCCEEDED');
  if (done.length === 0) return null;
  return (
    <Group gap={2} wrap="nowrap" justify="flex-end">
      {done.map((r) => (
        <TaxDocumentButton key={r.id} compact label={`Credit note ${r.creditNoteNumber ?? ''} (PDF)`.replace('  ', ' ')}
          doc={{ kind: 'creditNote', refundId: r.id, number: r.creditNoteNumber }} />
      ))}
    </Group>
  );
}
