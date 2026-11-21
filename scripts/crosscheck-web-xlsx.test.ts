// The web half of scripts/crosscheck-web-xlsx.sh, run by the web's own vitest so the web's real
// `readXlsx`/`writeXlsx` are what is exercised. The script copies this into the web test tree for one
// run and removes it afterwards.
//
// For every workbook the Android writer packaged: the web reads Android's package, reads its own
// package of the same model, and requires the two to read the same — then hands its own packages
// back for the Android side to check the same property in the other direction.
import { afterAll, describe, expect, it } from 'vitest';
import { readFileSync, writeFileSync } from 'node:fs';
import { writeXlsx } from '@/lib/ooxml/xlsx/write';
import { readXlsx } from '@/lib/ooxml/xlsx/read';

type Case = { name: string; model: Parameters<typeof writeXlsx>[0]; xlsx: string };

const input: { cases: Case[] } = JSON.parse(readFileSync(process.env.NEUTRINO_XLSX_CROSSCHECK_IN!, 'utf8'));
const produced: { name: string; xlsx: string }[] = [];
const bytes = (b64: string) => Uint8Array.from(Buffer.from(b64, 'base64'));

describe('Android xlsx cross-check', () => {
  it.each(input.cases.map((c) => [c.name, c] as const))('Android-written %s reads on the web as the web\'s own does', async (_name, c) => {
    const webBytes = await writeXlsx(c.model);
    produced.push({ name: c.name, xlsx: Buffer.from(webBytes).toString('base64') });
    expect(await readXlsx(bytes(c.xlsx))).toEqual(await readXlsx(webBytes));
  });

  afterAll(() => writeFileSync(process.env.NEUTRINO_XLSX_CROSSCHECK_OUT!, JSON.stringify({ cases: produced })));
});
