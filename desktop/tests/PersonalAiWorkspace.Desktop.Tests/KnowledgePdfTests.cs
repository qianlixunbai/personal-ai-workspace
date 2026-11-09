using System.IO;
using System.Security.Cryptography;
using System.Text;
using PersonalAiWorkspace.Core;
using Xunit;

namespace PersonalAiWorkspace.Desktop.Tests;

public sealed class KnowledgePdfTests
{
    // Independent tiny PDF fixture: Latin text plus a two-byte CID font with an explicit Unicode map.
    internal static byte[] Fixture(bool blankLastPage = false, int pages = 2, bool form = false)
    {
        var objects = new List<string>();
        string Stream(string value) => "<< /Length " + Encoding.ASCII.GetByteCount(value) + " >>\nstream\n" + value + "\nendstream";
        objects.Add("<< /Type /Catalog /Pages 2 0 R" + (form ? " /AcroForm << /Fields [] >>" : "") + " >>");
        objects.Add("<< /Type /Pages /Count " + pages + " /Kids [" + string.Join(" ", Enumerable.Range(0, pages).Select(n => (7 + n * 2) + " 0 R")) + "] >>");
        objects.Add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>");
        objects.Add("<< /Type /Font /Subtype /Type0 /BaseFont /STSong-Light /Encoding /Identity-H /DescendantFonts [5 0 R] /ToUnicode 6 0 R >>");
        objects.Add("<< /Type /Font /Subtype /CIDFontType0 /BaseFont /STSong-Light /CIDSystemInfo << /Registry (Adobe) /Ordering (GB1) /Supplement 0 >> /DW 1000 /FontDescriptor << /Type /FontDescriptor /FontName /STSong-Light /Flags 4 /FontBBox [0 -200 1000 900] /ItalicAngle 0 /Ascent 900 /Descent -200 /CapHeight 700 /StemV 80 >> >>");
        objects.Add(Stream("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n/CMapName /SyntheticUnicode def\n/CMapType 2 def\n1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n4 beginbfchar\n<0101> <4E2D>\n<0102> <6587>\n<0103> <6D4B>\n<0104> <8BD5>\nendbfchar\nendcmap\nCMapName currentdict /CMap defineresource pop\nend\nend"));
        for (int n = 0; n < pages; n++)
        {
            objects.Add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents " + (8 + n * 2) + " 0 R >>");
            string body = blankLastPage && n == pages - 1 ? "" : "BT /F1 12 Tf 40 780 Td (" + (n == 0 ? "NerionPDF synthetic archive. --inspect=on" : "Second page: retain exact source text.") + ") Tj /F2 12 Tf 0 -24 Td <0101010201030104> Tj ET";
            objects.Add(Stream(body));
        }
        var pdf = new StringBuilder("%PDF-1.7\n"); var offsets = new List<int>();
        for (int i = 0; i < objects.Count; i++) { offsets.Add(pdf.Length); pdf.Append(i + 1).Append(" 0 obj\n").Append(objects[i]).Append("\nendobj\n"); }
        int xref = pdf.Length; pdf.Append("xref\n0 ").Append(objects.Count + 1).Append("\n0000000000 65535 f \n");
        foreach (int offset in offsets) pdf.Append(offset.ToString("D10", System.Globalization.CultureInfo.InvariantCulture)).Append(" 00000 n \n");
        pdf.Append("trailer\n<< /Size ").Append(objects.Count + 1).Append(" /Root 1 0 R >>\nstartxref\n").Append(xref).Append("\n%%EOF\n");
        return Encoding.ASCII.GetBytes(pdf.ToString());
    }

    [Fact] public async Task ActualWorkerPreservesUnicodePageOrderAndFingerprintWithoutMutatingInput()
    {
        byte[] bytes = Fixture(); using var input = new MemoryStream(bytes, false);
        var excerpt = await KnowledgePdf.ReadAsync(input, "合成样本.pdf", default);
        Assert.Equal("合成样本.pdf.txt", excerpt.Filename);
        Assert.Contains(Convert.ToHexString(SHA256.HashData(bytes)), excerpt.Text);
        Assert.Contains("中文测试", excerpt.Text); Assert.Contains("NerionPDF synthetic archive. --inspect=on", excerpt.Text);
        Assert.Contains("--- PDF 第 2 页 ---\nSecond page: retain exact source text.", excerpt.Text);
        Assert.True(excerpt.Text.IndexOf("NerionPDF", StringComparison.Ordinal) < excerpt.Text.IndexOf("Second page", StringComparison.Ordinal));
        Assert.Contains(KnowledgePdf.Notice, excerpt.Text); Assert.Equal(bytes, input.ToArray()); Assert.True(input.CanRead);
    }

    [Fact] public void MixedTextAndBlankPageOrFormsAreRefusedWithoutPartialText()
    {
        foreach (byte[] bytes in new[] { Fixture(blankLastPage: true), Fixture(form: true) })
        { var result = KnowledgePdf.Extract(bytes); Assert.Equal("UNSUPPORTED", result.Error); Assert.Null(result.Text); }
    }

    [Fact] public void CorruptAndOversizedPageTreesProduceNoPartialResult()
    {
        var corrupt = KnowledgePdf.Extract("%PDF-1.7\ninvalid"u8.ToArray()); Assert.Equal("INVALID", corrupt.Error); Assert.Null(corrupt.Text);
        var tooMany = KnowledgePdf.Extract(Fixture(pages: 201)); Assert.Equal("LIMIT", tooMany.Error); Assert.Null(tooMany.Text);
    }

    [Fact] public async Task CancellationAndInvalidWorkerInputProduceNoExcerpt()
    {
        using var stop = new CancellationTokenSource(); stop.Cancel(); using var input = new MemoryStream(Fixture(), false);
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => KnowledgePdf.ReadAsync(input, "fixture.pdf", stop.Token));
        using var invalid = new MemoryStream("%PDF-1.7 broken"u8.ToArray(), false);
        Assert.Equal(DesktopError.KnowledgePdfInvalid, (await Assert.ThrowsAsync<DesktopException>(() => KnowledgePdf.ReadAsync(invalid, "broken.pdf", default))).Error);
    }
}
