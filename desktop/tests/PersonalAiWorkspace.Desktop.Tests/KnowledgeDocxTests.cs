using System.IO;
using System.IO.Compression;
using System.Security.Cryptography;
using System.Text;
using PersonalAiWorkspace.Core;
using Xunit;

namespace PersonalAiWorkspace.Desktop.Tests;

public sealed class KnowledgeDocxTests
{
    internal const string Body="<w:p><w:r><w:t>NerionDOCX 合成规则：月影站点仅限演练，禁止生产流量。😀</w:t></w:r></w:p>"+
        "<w:tbl><w:tr><w:tc><w:p><w:r><w:t>参数</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>条件</w:t></w:r></w:p></w:tc></w:tr>"+
        "<w:tr><w:tc><w:p><w:r><w:t>--inspect=on</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>只校验，不写入；保留 &quot;引号&quot;。</w:t></w:r></w:p></w:tc></w:tr></w:tbl>"+
        "<w:p><w:hyperlink r:id=\"link\"><w:r><w:t>链接显示文字</w:t></w:r></w:hyperlink></w:p>";
    internal static byte[] Fixture(string body=Body,string declaration="")
    {
        using var bytes=new MemoryStream();
        using(var zip=new ZipArchive(bytes,ZipArchiveMode.Create,true)){
            void Entry(string path,string content){using var writer=new StreamWriter(zip.CreateEntry(path).Open(),new UTF8Encoding(false));writer.Write(content);}
            Entry("[Content_Types].xml","<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>");
            Entry("_rels/.rels","<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"main\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>");
            Entry("word/document.xml",declaration+"<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><w:body>"+body+"</w:body></w:document>");
            Entry("word/_rels/document.xml.rels","<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"link\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink\" Target=\"https://example.invalid/do-not-fetch\" TargetMode=\"External\"/></Relationships>");
        }
        return bytes.ToArray();
    }
    [Fact]public void BodyAndTableTextBecomeAnExplicitUtf8ExcerptWithSourceFingerprint()
    {
        byte[] bytes=Fixture(Body+"<w:p><w:r><w:t>直接 Unicode 文字：α = 1，✓。</w:t></w:r></w:p>");using var input=new MemoryStream(bytes,false);
        var result=KnowledgeDocx.Read(input,"合成示例.docx",default);
        Assert.Equal("合成示例.docx.txt",result.Filename);
        Assert.Contains(Convert.ToHexString(SHA256.HashData(bytes)),result.Text);
        Assert.Contains("NerionDOCX 合成规则：月影站点仅限演练，禁止生产流量。😀\n",result.Text);
        Assert.Contains("\"参数\"\t\"条件\"\n\"--inspect=on\"\t\"只校验，不写入；保留 \"\"引号\"\"。\"\n",result.Text);
        Assert.Contains("链接显示文字\n",result.Text);Assert.DoesNotContain("https://",result.Text);
        Assert.Contains("直接 Unicode 文字：α = 1，✓。\n",result.Text);
        Assert.Contains(KnowledgeDocx.Notice,result.Text);Assert.Equal(bytes,input.ToArray());Assert.True(input.CanRead);
    }
    [Fact]public void RevisionsFieldsAndMergedCellsAreRefusedInsteadOfSilentlyChangingMeaning()
    {
        foreach(string body in new[]{"<w:p><w:ins><w:r><w:t>未确认修订</w:t></w:r></w:ins></w:p>","<w:p><w:fldSimple w:instr=\"DATE\"><w:r><w:t>旧日期</w:t></w:r></w:fldSimple></w:p>","<w:tbl><w:tr><w:tc><w:tcPr><w:gridSpan w:val=\"2\"/></w:tcPr><w:p><w:r><w:t>跨列</w:t></w:r></w:p></w:tc></w:tr></w:tbl>"}){
            using var file=new MemoryStream(Fixture(body));
            Assert.Equal(DesktopError.KnowledgeDocxUnsupported,Assert.Throws<DesktopException>(()=>KnowledgeDocx.Read(file,"fixture.docx",default)).Error);
        }
    }
    [Fact]public void BoundedZipAndXmlRejectExpansionDtdAndCorruptInput()
    {
        var cases=new[]{Fixture("<w:p><w:r><w:t>"+new string('x',4*1024*1024)+"</w:t></w:r></w:p>"),Fixture("<w:p><w:r><w:t>&secret;</w:t></w:r></w:p>","<!DOCTYPE w:document [<!ENTITY secret SYSTEM 'file:///unread-secret'>]>"),new byte[]{1,2,3}};
        foreach(var bytes in cases){using var file=new MemoryStream(bytes);Assert.Equal(DesktopError.KnowledgeDocxInvalid,Assert.Throws<DesktopException>(()=>KnowledgeDocx.Read(file,"fixture.docx",default)).Error);}
    }
    [Fact]public void CancellationProducesNoExcerptAndImageOnlyIsNotPretendedToBeText()
    {
        using var cancelled=new CancellationTokenSource();cancelled.Cancel();using var file=new MemoryStream(Fixture());
        Assert.Throws<OperationCanceledException>(()=>KnowledgeDocx.Read(file,"fixture.docx",cancelled.Token));
        using var image=new MemoryStream(Fixture("<w:p><w:r><w:drawing/></w:r></w:p>"));
        Assert.Equal(DesktopError.KnowledgeDocxInvalid,Assert.Throws<DesktopException>(()=>KnowledgeDocx.Read(image,"fixture.docx",default)).Error);
    }
    internal const string MathBody="<w:p><w:r><w:t>公式结果如下：</w:t></w:r><m:oMath xmlns:m=\"http://schemas.openxmlformats.org/officeDocument/2006/math\"><m:r><m:t>x = 1</m:t></m:r></m:oMath></w:p>";
    internal const string SymbolBody="<w:p><w:r><w:t>状态符号：</w:t><w:sym w:font=\"Wingdings\" w:char=\"F0FC\"/></w:r></w:p>";
    [Theory]
    [InlineData(MathBody)]
    [InlineData(SymbolBody)]
    public void EquationAndFontMappedSymbolCannotDisappearFromAnAcceptedExcerpt(string body)
    {
        using var file=new MemoryStream(Fixture(body));
        Assert.Equal(DesktopError.KnowledgeDocxUnsupported,Assert.Throws<DesktopException>(()=>KnowledgeDocx.Read(file,"formula.docx",default)).Error);
    }
}
