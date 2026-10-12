import { notifications } from '@mantine/notifications';
import { ApiError } from '@/api/client';

export function errorMessage(err: unknown): string {
  if (err instanceof ApiError) return err.details;
  if (err instanceof Error) return err.message;
  return 'Something went wrong';
}

export function notifyError(err: unknown, title = 'Request failed') {
  notifications.show({ color: 'red', title, message: errorMessage(err), autoClose: 7000 });
}

export function notifySuccess(message: string, title?: string) {
  notifications.show({ color: 'teal', title, message, autoClose: 3500 });
}
