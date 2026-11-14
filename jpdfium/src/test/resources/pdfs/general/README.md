# General PDF fixtures

Fixtures without a home in a more specific directory. Follows the provenance
style of [`../external/README.md`](../external/README.md).

## From mozilla/pdf.js

From [mozilla/pdf.js](https://github.com/mozilla/pdf.js), Apache License 2.0,
`test/pdfs/`:

| File | Why it is unusual |
|---|---|
| `90ms_rksj_h_sample.pdf` | Adobe-Japan1 text through the predefined `90ms-RKSJ-H` CMap (`HeiseiMin-W3`); embedding must leave the font unresolved with a CID-encoding reason instead of substituting the CID program |
| `issue11915.pdf` | Regression of [pdf.js #11915](https://github.com/mozilla/pdf.js/issues/11915), garbled display of fonts |
| `issue13916.pdf` | Regression of [pdf.js #13916](https://github.com/mozilla/pdf.js/issues/13916), embedded font displayed with wrong (Czech) characters |
| `issue20065.pdf` | Byte-identical to `test/pdfs/issue20065.pdf` in mozilla/pdf.js (git blob `f13b4a4c1d838e69560c04e9d0e609913cc5e314`); an Adobe Acrobat 25.1-produced PDF 1.6 document exercising CID-keyed font handling |

## From pdfcpu

`basic_ResourceDictInheritanceDemo.pdf` is a [pdfcpu](https://github.com/pdfcpu/pdfcpu)
sample (Apache License 2.0), internal producer `pdfcpu v0.3.13 dev`: its font is
reachable only through the page resource-inheritance chain, so an inventory that
only walks page dictionaries misses it.

## Synthesized for this suite

Tiny hand-built PDFs, no external content, committed by the JPDFium authors:

| File | Why it is unusual |
|---|---|
| `checkbox-zadb-appearance.pdf` | AcroForm checkbox whose widget appearance streams reference the `/ZaDb` (Zapf Dingbats) default resource |
| `inline-font-resources.pdf` | Fonts declared in inline page and AcroForm `/DR` dictionaries only, with no page resource dictionary of their own |
| `nested-type3-reverse-order.pdf` | Nested Type3 fonts whose resource dictionaries appear in reverse object order |
| `three-pages-shared-font.pdf` | Three pages sharing one font resource; page-range inventories must stay compact |
| `utf8-name-font.pdf` | `/BaseFont` name with non-ASCII UTF-8 bytes that must round-trip through inventory reports |
| `gb_euc_h_sample.pdf` | Hand-built Type0 sample: `SimSun` base font with an `Adobe-GB1-0` CIDSystemInfo and a `/GB-EUC-H` encoding; draws "Hello ASCII" and `<D6D0CEC4B2E2CAD4>` (GB-EUC-H bytes for 中文测试), exercising GB-EUC-H CID handling |
| `ksc_euc_h_sample.pdf` | Hand-built Type0 sample: `Batang` base font with an `Adobe-Korea1-0` CIDSystemInfo and a `/KSC-EUC-H` encoding; draws "Hello ASCII" and `<C7D1B9BEEE20C5D7BDBAC6AE>` (KSC-EUC-H bytes for 한국어 테스트), exercising KSC-EUC-H CID handling |
