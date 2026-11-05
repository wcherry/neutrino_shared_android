// The web half of scripts/crosscheck-web-docx.sh, run by the web's own vitest so the web's real
// `readDocx`/`writeDocx` (and the `docx` package and jsdom they need) are what is exercised. The
// script copies this into the web test tree for one run and removes it afterwards.
//
// For every document the Android writer packaged: the web reads Android's package, reads its own
// package of the same document, and requires the two to read the same — then hands its own
// packages back for the Android side to check the same property in the other direction.
import { afterAll, describe, expect, it } from 'vitest';
import { readFileSync, writeFileSync } from 'node:fs';
import { writeDocx } from '@/lib/ooxml/docx/write';
import { readDocx } from '@/lib/ooxml/docx/read';

type Case = { name: string; model: { doc: unknown; _meta: unknown }; images: Record<string, string>; docx: string };

const input: { cases: Case[] } = JSON.parse(readFileSync(process.env.NEUTRINO_DOCX_CROSSCHECK_IN!, 'utf8'));
const produced: { name: string; docx: string }[] = [];
const bytes = (b64: string) => Uint8Array.from(Buffer.from(b64, 'base64'));

describe('Android docx cross-check', () => {
  it.each(input.cases.map((c) => [c.name, c] as const))('Android-written %s reads on the web as the web\'s own does', async (_name, c) => {
    const images = new Map(Object.entries(c.images).map(([src, b64]) => [src, bytes(b64)]));
    const model = { doc: c.model.doc, meta: c.model._meta } as Parameters<typeof writeDocx>[0];
    const webBytes = await writeDocx(model, { title: 'Cross-check', images });
    produced.push({ name: c.name, docx: Buffer.from(webBytes).toString('base64') });
    expect(await readDocx(bytes(c.docx))).toEqual(await readDocx(webBytes));
  });

  afterAll(() => writeFileSync(process.env.NEUTRINO_DOCX_CROSSCHECK_OUT!, JSON.stringify({ cases: produced })));
});
