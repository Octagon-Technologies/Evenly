/**
 * A picked receipt file -> one page for the scan. Photos are re-encoded as JPEG at most 2000px on the long
 * side: a phone photo is 3-12 MB, the model reads 2000px fine, and the upload is a guest's mobile data.
 * A PDF, or a photo this browser cannot decode (HEIC outside Safari), goes up as it is.
 */
import type { ScanPage } from './api.ts';

const MAX_SIDE = 2000;
const RAW_TYPES = new Set(['image/jpeg', 'image/png', 'image/webp', 'image/heic', 'image/heif', 'application/pdf']);

export async function toScanPage(file: File): Promise<ScanPage | null> {
  if (file.type.startsWith('image/')) {
    try {
      const bitmap = await createImageBitmap(file);
      const scale = Math.min(1, MAX_SIDE / Math.max(bitmap.width, bitmap.height));
      const canvas = document.createElement('canvas');
      canvas.width = Math.round(bitmap.width * scale);
      canvas.height = Math.round(bitmap.height * scale);
      canvas.getContext('2d')?.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
      bitmap.close();
      const blob = await new Promise<Blob | null>((done) => canvas.toBlob(done, 'image/jpeg', 0.85));
      if (blob) return { data: await base64(blob), mediaType: 'image/jpeg' };
    } catch {
      // Fall through and send the original bytes.
    }
  }
  if (!RAW_TYPES.has(file.type)) return null;
  return { data: await base64(file), mediaType: file.type };
}

async function base64(blob: Blob): Promise<string> {
  const bytes = new Uint8Array(await blob.arrayBuffer());
  let bin = '';
  for (let i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(bin);
}
