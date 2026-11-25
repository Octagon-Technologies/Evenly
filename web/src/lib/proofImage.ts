/**
 * A picked or pasted payment screenshot, made small enough to post through `web-claim` (6 MB cap). Same
 * idea as the app's `ImageProcessor.compress`: re-encode as JPEG, longest side at most [MAX_SIDE].
 */
import type { ProofFile } from './api.ts';

const MAX_SIDE = 1800;
const QUALITY = 0.82;
const PASSTHROUGH = new Set(['image/jpeg', 'image/png', 'image/webp']);

export class ProofImageError extends Error {}

export async function toProofFile(file: File): Promise<ProofFile> {
  if (!file.type.startsWith('image/')) throw new ProofImageError('Pick an image, like a screenshot.');
  try {
    const bitmap = await createImageBitmap(file);
    const scale = Math.min(1, MAX_SIDE / Math.max(bitmap.width, bitmap.height));
    const canvas = document.createElement('canvas');
    canvas.width = Math.round(bitmap.width * scale);
    canvas.height = Math.round(bitmap.height * scale);
    canvas.getContext('2d')?.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
    bitmap.close();
    const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/jpeg', QUALITY));
    if (blob) return { data: await base64(blob), mimeType: 'image/jpeg' };
  } catch {
    // A format the browser cannot decode (HEIC outside Safari) falls through to sending it as is.
  }
  if (!PASSTHROUGH.has(file.type)) throw new ProofImageError('That image type does not work here. Try a PNG or JPEG screenshot.');
  return { data: await base64(file), mimeType: file.type };
}

async function base64(blob: Blob): Promise<string> {
  const url = await new Promise<string>((resolve, reject) => {
    const r = new FileReader();
    r.onload = () => resolve(r.result as string);
    r.onerror = () => reject(r.error);
    r.readAsDataURL(blob);
  });
  return url.slice(url.indexOf(',') + 1);
}
