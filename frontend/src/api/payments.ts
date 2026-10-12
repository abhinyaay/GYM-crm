import type { Payment, PaymentMethod, PaymentProvider } from './types';

export type MandateStatus = 'NONE' | 'PENDING' | 'ACTIVE' | 'PAUSED' | 'REVOKED';

/** A card / UPI handle tokenised by the payment provider (no card number is ever stored). */
export interface SavedPaymentMethod {
  id: number;
  memberId: number;
  provider: PaymentProvider;
  type: 'CARD' | 'UPI';
  label: string;
  brand: string | null;
  last4: string | null;
  expMonth: number | null;
  expYear: number | null;
  vpaMasked: string | null;
  isDefault: boolean;
  status: 'ACTIVE' | 'REVOKED' | 'EXPIRED';
  mandateStatus: MandateStatus;
  mandateMaxAmount: number | null;
  mandateExpiresAt: string | null;
  createdAt: string;
}

export interface PaymentConfig {
  provider: PaymentProvider;
  client: { mode: 'simulated' | 'razorpay'; keyId?: string; businessName?: string; checkoutScript?: string };
  onlineMethods: PaymentMethod[];
  offlineMethods: PaymentMethod[];
  emiTenures: number[];
  emiMinAmount: number;
  mandateMaxAmount: number;
  testTokensEnabled: boolean;
}

export type IntentStatus = 'CREATED' | 'SUCCEEDED' | 'FAILED' | 'EXPIRED' | 'CANCELLED';

export interface CheckoutSession {
  intentId: number;
  status: IntentStatus;
  provider: PaymentProvider;
  orderId: string;
  amountMinor: number;
  amount: number;
  currency: string;
  method: PaymentMethod;
  emiTenureMonths: number | null;
  saveMethod: boolean;
  setupAutopay: boolean;
  customerId: string | null;
  mandateMaxAmount: number | null;
  expiresAt: string;
  invoiceNumber: string;
  description: string;
  prefill: { name: string; email: string | null; contact: string | null };
  client: PaymentConfig['client'];
}

/** What the provider's checkout hands back to the browser. */
export interface CheckoutCompletion {
  providerPaymentId: string;
  providerOrderId: string;
  signature: string;
}

export type CheckoutOutcome = 'SUCCEEDED' | 'FAILED' | 'PENDING' | 'ALREADY_PROCESSED' | 'REFUNDED_DUPLICATE';

export interface CheckoutResult {
  intentId: number;
  status: IntentStatus;
  outcome: CheckoutOutcome;
  payment: Payment | null;
  failureReason: string | null;
  savedPaymentMethodId: number | null;
  autopayEnabled: boolean;
}

export interface CreditEntry {
  id: number;
  entryType: 'ISSUED' | 'REFUND' | 'PAYMENT' | 'ADJUSTMENT';
  amount: number;
  balanceAfter: number;
  reason: string;
  paymentId: number | null;
  refundId: number | null;
  createdAt: string;
}

export interface CreditAccount {
  memberId: number;
  balance: number;
  entries: CreditEntry[];
}

export type AutopayStatus = 'SCHEDULED' | 'PROCESSING' | 'SUCCEEDED' | 'FAILED' | 'SKIPPED' | 'CANCELLED';

export interface AutopayAttempt {
  id: number;
  subscriptionId: number;
  renewalSubscriptionId: number;
  invoiceId: number;
  invoiceNumber: string;
  memberId: number;
  memberName: string;
  paymentMethodId: number;
  paymentMethodLabel: string;
  amount: number;
  attemptNo: number;
  scheduledAt: string;
  executedAt: string | null;
  status: AutopayStatus;
  paymentId: number | null;
  failureReason: string | null;
  preDebitNotifiedAt: string | null;
}

export interface UpcomingRenewal {
  subscriptionId: number;
  memberId: number;
  memberCode: string;
  memberName: string;
  planName: string;
  endDate: string;
  planPrice: number;
  paymentMethodId: number | null;
  paymentMethodLabel: string | null;
  methodUsable: boolean;
}

export interface AutopayRunSummary {
  renewalsScheduled: number;
  debitsAttempted: number;
  succeeded: number;
  failed: number;
  failures: number;
}

export const METHOD_LABELS: Record<PaymentMethod, string> = {
  CASH: 'Cash',
  CARD: 'Card',
  UPI: 'UPI',
  BANK_TRANSFER: 'Bank transfer',
  NET_BANKING: 'Net banking',
  WALLET: 'Wallet',
  EMI: 'Card EMI',
  STORE_CREDIT: 'Store credit',
};

export const ALL_METHODS = Object.keys(METHOD_LABELS) as PaymentMethod[];
