import type { CheckoutCompletion, CheckoutSession } from '@/api/payments';

interface RazorpayResponse {
  razorpay_payment_id: string;
  razorpay_order_id: string;
  razorpay_signature: string;
}

interface RazorpayFailure {
  error?: { description?: string; reason?: string };
}

interface RazorpayInstance {
  open: () => void;
  on: (event: 'payment.failed', handler: (r: RazorpayFailure) => void) => void;
}

declare global {
  interface Window {
    Razorpay?: new (options: Record<string, unknown>) => RazorpayInstance;
  }
}

let loading: Promise<void> | null = null;

function loadScript(src: string): Promise<void> {
  if (window.Razorpay) return Promise.resolve();
  loading ??= new Promise<void>((resolve, reject) => {
    const s = document.createElement('script');
    s.src = src;
    s.async = true;
    s.onload = () => resolve();
    s.onerror = () => {
      loading = null;
      reject(new Error('Could not load Razorpay Checkout'));
    };
    document.body.appendChild(s);
  });
  return loading;
}

/** Thrown when the customer closes the checkout without paying. */
export class CheckoutDismissed extends Error {
  constructor() {
    super('Checkout closed');
  }
}

/**
 * Opens Razorpay Checkout for a server-created order. Card / UPI details are entered on Razorpay's
 * page only; we receive the payment id, order id and signature, which the server verifies.
 * See https://razorpay.com/docs/payments/payment-gateway/web-integration/standard/integration-steps/
 */
export async function openRazorpayCheckout(session: CheckoutSession): Promise<CheckoutCompletion> {
  await loadScript(session.client.checkoutScript ?? 'https://checkout.razorpay.com/v1/checkout.js');
  const Razorpay = window.Razorpay;
  if (!Razorpay) throw new Error('Razorpay Checkout is unavailable');
  return new Promise<CheckoutCompletion>((resolve, reject) => {
    const options: Record<string, unknown> = {
      key: session.client.keyId,
      amount: session.amountMinor,
      currency: session.currency,
      order_id: session.orderId,
      name: session.client.businessName ?? 'GymOps',
      description: session.description,
      prefill: { name: session.prefill.name, email: session.prefill.email ?? undefined, contact: session.prefill.contact ?? undefined },
      notes: { invoice: session.invoiceNumber },
      theme: { color: '#228be6' },
      handler: (r: RazorpayResponse) => resolve({
        providerPaymentId: r.razorpay_payment_id,
        providerOrderId: r.razorpay_order_id,
        signature: r.razorpay_signature,
      }),
      modal: { ondismiss: () => reject(new CheckoutDismissed()) },
    };
    if (session.customerId) {
      options.customer_id = session.customerId;
      if (session.saveMethod) options.recurring = 1;   // registers a reusable token (and the autopay mandate)
    }
    const rzp = new Razorpay(options);
    // the checkout stays open after a failed attempt so the customer can retry; we just log it
    rzp.on('payment.failed', (r) => console.warn('Razorpay attempt failed', r.error?.description ?? r.error?.reason));
    rzp.open();
  });
}
