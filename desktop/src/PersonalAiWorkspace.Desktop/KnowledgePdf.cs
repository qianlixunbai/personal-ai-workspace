using System;
using System.Diagnostics;
using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Threading;
using System.Threading.Tasks;
using PersonalAiWorkspace.Core;
using UglyToad.PdfPig;
using UglyToad.PdfPig.DocumentLayoutAnalysis.TextExtractor;

namespace PersonalAiWorkspace.Desktop;

internal static class KnowledgePdf
{
    internal const string WorkerArgument = "--pdf-text-worker";
    internal const int MaximumPages = 200, MaximumTextUnits = 240_000;
    internal const string Notice = "仅保存 PDF 文字层摘录，不保存原 PDF、图片、批注、附件或版式，不进行 OCR。逐页标记为文件内页序号，不是印刷页码；表格、多栏和文字顺序可能与原件不同，请对照原件核对。加密、表单、任一页无可提取文字或超出限制时整份拒绝，不保存部分结果。限制：8 MiB、200 页、24 万字符，解析最长 12 秒。";
    internal sealed record Excerpt(string Filename, string Text) { public override string ToString() => "PdfExcerpt[redacted]"; }
    private sealed record Input(byte[] Bytes) { public override string ToString() => "PdfInput[redacted]"; }
    internal sealed record Result(string? Text, int Pages, string? Error) { public override string ToString() => "PdfResult[redacted]"; }

    internal static async Task<Excerpt> ReadAsync(Stream input, string filename, CancellationToken token)
    {
        try
        {
            token.ThrowIfCancellationRequested();
            if (!filename.EndsWith(".pdf", StringComparison.OrdinalIgnoreCase) || !input.CanSeek || input.Length <= 0)
                throw new DesktopException(DesktopError.KnowledgePdfInvalid);
            string outputName = filename + ".txt"; RuntimeClient.ValidateKnowledgeFilename(outputName);
            if (input.Length > RuntimeClient.MaximumKnowledgeSourceBytes) throw new DesktopException(DesktopError.KnowledgeSourceTooLarge);
            input.Position = 0; var bytes = new byte[checked((int)input.Length)];
            await input.ReadExactlyAsync(bytes, token);
            // Assign the worker before sending input. Closing this sole Job handle kills it even if the app exits.
            using var job = RuntimeProcessJob.CreateDocumentParser();
            var result = await HelperProcess.RunAsync<Result>(WorkerArgument, [], 2 * 1024 * 1024,
                TimeSpan.FromSeconds(12), token, new Input(bytes), onStarted: pid =>
                { using var process = Process.GetProcessById(pid); job.Assign(process); });
            if (result is null) throw new DesktopException(DesktopError.KnowledgePdfInvalid);
            if (result.Error is not null) throw new DesktopException(result.Error switch
            {
                "UNSUPPORTED" => DesktopError.KnowledgePdfUnsupported,
                "LIMIT" => DesktopError.KnowledgePdfLimit,
                _ => DesktopError.KnowledgePdfInvalid
            });
            if (result.Pages is < 1 or > MaximumPages || string.IsNullOrWhiteSpace(result.Text) || result.Text.Length > MaximumTextUnits)
                throw new DesktopException(DesktopError.KnowledgePdfInvalid);
            ValidateText(result.Text);
            string text = "PDF 文字层摘录\n来源文件：" + filename + "\n原文件 SHA-256：" + Convert.ToHexString(SHA256.HashData(bytes))
                + "\n提取格式：pdf-text-v1\n文件页数：" + result.Pages + "\n" + Notice + "\n\n--- 正文 ---\n" + result.Text;
            if (new UTF8Encoding(false, true).GetByteCount(text) > 1024 * 1024) throw new DesktopException(DesktopError.KnowledgePdfLimit);
            return new(outputName, text);
        }
        catch (HelperTimeoutException) { throw new DesktopException(DesktopError.KnowledgePdfLimit); }
        catch (OperationCanceledException) { throw; }
        catch (DesktopException) { throw; }
        catch (Exception) { throw new DesktopException(DesktopError.KnowledgePdfInvalid); }
    }

    // Terminal helper mode: stdin/stdout only, never opens a profile, Runtime or original file path.
    internal static int RunWorker()
    {
        Result result;
        try
        {
            using var input = Console.OpenStandardInput(); using var buffer = new MemoryStream();
            var chunk = new byte[65536]; int count;
            while ((count = input.Read(chunk)) > 0)
            {
                if (buffer.Length + count > 12 * 1024 * 1024) throw new InvalidDataException();
                buffer.Write(chunk, 0, count);
            }
            var request = JsonSerializer.Deserialize<Input>(buffer.ToArray(), new JsonSerializerOptions { UnmappedMemberHandling = JsonUnmappedMemberHandling.Disallow });
            result = Extract(request?.Bytes ?? []);
        }
        catch (Exception) { result = new(null, 0, "INVALID"); }
        Console.Out.Write(JsonSerializer.Serialize(result)); return 0;
    }

    internal static Result Extract(byte[] bytes)
    {
        try
        {
            if (bytes.Length < 8 || bytes.Length > RuntimeClient.MaximumKnowledgeSourceBytes || !bytes.AsSpan().StartsWith("%PDF-"u8))
                return new(null, 0, "INVALID");
            using var pdf = PdfDocument.Open(bytes, new ParsingOptions { UseLenientParsing = false, SkipMissingFonts = false });
            if (pdf.IsEncrypted || pdf.TryGetForm(out _)) return new(null, 0, "UNSUPPORTED");
            if (pdf.NumberOfPages is < 1 or > MaximumPages) return new(null, 0, "LIMIT");
            var text = new StringBuilder();
            foreach (var page in pdf.GetPages())
            {
                string body = ContentOrderTextExtractor.GetText(page).ReplaceLineEndings("\n");
                if (string.IsNullOrWhiteSpace(body)) return new(null, 0, "UNSUPPORTED");
                ValidateText(body);
                string marker = "--- PDF 第 " + page.Number + " 页 ---\n";
                if (text.Length + marker.Length + body.Length + 2 > MaximumTextUnits) return new(null, 0, "LIMIT");
                text.Append(marker).Append(body).Append("\n\n");
            }
            return new(text.ToString(), pdf.NumberOfPages, null);
        }
        catch (Exception) { return new(null, 0, "INVALID"); }
    }

    private static void ValidateText(string text)
    {
        _ = new UTF8Encoding(false, true).GetByteCount(text);
        foreach (char c in text) if (c == '\uFFFD' || (char.IsControl(c) && c is not '\n' and not '\t')) throw new InvalidDataException();
    }
}
