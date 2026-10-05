using System.Buffers.Binary;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Windows;
using System.Windows.Threading;
using PersonalAiWorkspace.Core;
using Xunit;
using static PersonalAiWorkspace.Desktop.Tests.MemoryClientTests;

namespace PersonalAiWorkspace.Desktop.Tests;

public sealed class MemoryBackupTests
{
    internal static string Backup(int count = 1, string? content = null)
    {
        const string time = "2026-10-03T10:00:00Z";
        var rows = Enumerable.Range(0, count).Select(_ => new
        {
            id = Guid.NewGuid().ToString("D"), type = "PROJECT_NOTE", title = PrivateTitle, content = content ?? PrivateContent,
            status = "ACTIVE", revision = 3L, source = "MANUAL", createdAt = time, updatedAt = time
        }).OrderBy(row => row.id, StringComparer.Ordinal).ToArray();
        using var canonical = new MemoryStream();
        void Add(string text)
        {
            byte[] bytes = Encoding.UTF8.GetBytes(text); byte[] length = new byte[4];
            BinaryPrimitives.WriteInt32BigEndian(length, bytes.Length); canonical.Write(length); canonical.Write(bytes);
        }
        foreach (var value in new[] { "personal-ai-workspace.memory-backup", "1", "1", time, count.ToString(System.Globalization.CultureInfo.InvariantCulture) }) Add(value);
        foreach (var row in rows)
            foreach (var value in new[] { row.id, row.type, row.title, row.content, row.status, "3", row.source, row.createdAt, row.updatedAt }) Add(value);
        return JsonSerializer.Serialize(new { format = "personal-ai-workspace.memory-backup", formatVersion = 1, schemaVersion = 1,
            createdAt = time, itemCount = count, contentDigest = Convert.ToHexStringLower(SHA256.HashData(canonical.ToArray())), items = rows });
    }
    private static string Metadata(string backup)
    {
        using var body = JsonDocument.Parse(backup); var root = body.RootElement;
        return JsonSerializer.Serialize(new { formatVersion = 1, schemaVersion = 1, itemCount = root.GetProperty("itemCount").GetInt32(),
            contentDigest = root.GetProperty("contentDigest").GetString() });
    }
    private sealed class Files : IMemoryBackupFiles
    {
        internal MemoryExportDestination? Destination = new("private-selected-export.json", false);
        internal string? BackupPath = "private-selected-backup.json", Target = System.IO.Path.GetTempPath();
        internal int Reads, Writes, Targets;
        internal byte[] Bytes = Encoding.UTF8.GetBytes(Backup());
        internal TaskCompletionSource<byte[]>? Pending;
        internal CancellationToken PendingToken;
        public MemoryExportDestination? PickExport(Window _) => Destination;
        public string? PickBackup(Window _) => BackupPath;
        public string? PickTarget(Window _) { Targets++; return Target; }
        public Task<byte[]> ReadAsync(string _, CancellationToken token)
        { Reads++; PendingToken = token; return Pending?.Task ?? Task.FromResult(Bytes); }
        public Task WriteAsync(MemoryExportDestination _, ReadOnlyMemory<byte> bytes, CancellationToken token)
        { token.ThrowIfCancellationRequested(); Writes++; Assert.True(bytes.Span.SequenceEqual(Bytes), "Export bytes differ."); return Task.CompletedTask; }
    }
    [Fact] public Task ExplicitOnlyAndEveryPickerCancellationAvoidRuntimeAndFileOperations() => StaAsync(async () =>
    {
        int calls = 0; using var runtime = new RuntimeClient(new Handler((_, _) => { calls++; throw new InvalidOperationException(); }), () => Token);
        var files = new Files { Destination = null, BackupPath = null }; var window = new MemoryBackupWindow(runtime, files);
        Assert.Equal(0, calls); Assert.Contains("plaintext", window.PlaintextWarning.Text); Assert.Contains("Protect this file", window.PlaintextWarning.Text);
        Assert.Contains("NEW", window.RestoreWarning.Text); Assert.Contains("does not merge or overwrite", window.RestoreWarning.Text);
        await window.ExportAsync(); await window.RestoreAsync(); Assert.Equal(0, files.Targets);
        files.BackupPath = "selected"; files.Target = null; await window.RestoreAsync();
        Assert.Equal(0, calls); Assert.Equal(0, files.Reads); Assert.Equal(0, files.Writes); window.Close();
    });
    [Fact] public Task ExportAndRestoreUseNativeTransportAndShowOnlyMetadata() => StaAsync(async () =>
    {
        var files = new Files(); int calls = 0;
        using var runtime = new RuntimeClient(new Handler(async (request, token) =>
        {
            calls++; Assert.Equal("Bearer", request.Headers.Authorization?.Scheme); Assert.False(request.Headers.Contains("Origin"));
            if (request.Method == HttpMethod.Get) { Assert.Equal("/api/v1/memory/backup", request.RequestUri!.AbsolutePath); return Response(Encoding.UTF8.GetString(files.Bytes)); }
            Assert.Equal("/api/v1/memory/backup/restore", request.RequestUri!.AbsolutePath);
            using var payload = JsonDocument.Parse(await request.Content!.ReadAsByteArrayAsync(token));
            Assert.Equal(2, payload.RootElement.EnumerateObject().Count()); Assert.Equal(files.Target, payload.RootElement.GetProperty("targetDirectory").GetString());
            return Response(Metadata(Encoding.UTF8.GetString(files.Bytes)));
        }), () => Token);
        var window = new MemoryBackupWindow(runtime, files); await window.ExportAsync(); Assert.Equal(1, files.Writes); Safe(window.StatusText.Text);
        await window.RestoreAsync(); Assert.Contains("Restore complete", window.StatusText.Text); Assert.Contains("Start Runtime", window.StatusText.Text);
        Safe(window.StatusText.Text); Assert.DoesNotContain(files.Target!, window.StatusText.Text); Assert.Equal(2, calls); window.Close();
    });
    [Fact] public Task InvalidOrOversizedFileFailsBeforeAnyRestoreRequest() => StaAsync(async () =>
    {
        int calls = 0; using var runtime = new RuntimeClient(new Handler((_, _) => { calls++; throw new InvalidOperationException(); }), () => Token);
        var files = new Files { Bytes = Encoding.UTF8.GetBytes("{secret-content") }; var window = new MemoryBackupWindow(runtime, files);
        await window.RestoreAsync(); Assert.Contains("invalid or damaged", window.StatusText.Text); Safe(window.StatusText.Text);
        files.Bytes = new byte[RuntimeClient.MaximumBackupBytes + 1]; await window.RestoreAsync(); Assert.Contains("size budget", window.StatusText.Text);
        Assert.Equal(0, calls); window.Close();
    });
    [Fact] public Task ClosingCancelsLocalFileReadAndLateCompletionCannotSendRestoreOrRepopulate() => StaAsync(async () =>
    {
        int calls = 0; using var runtime = new RuntimeClient(new Handler((_, _) => { calls++; throw new InvalidOperationException(); }), () => Token);
        var files = new Files { Pending = new() }; var window = new MemoryBackupWindow(runtime, files);
        var pending = window.RestoreAsync(); Assert.False(window.RestoreButton.IsEnabled); window.Close();
        Assert.True(files.PendingToken.IsCancellationRequested); files.Pending.SetResult(files.Bytes); await pending;
        Assert.Equal(0, calls); Assert.Empty(window.StatusText.Text);
    });
    [Fact] public Task ClosingDuringExportCancelsHttpAndLateResponseCannotWriteOrRepopulate() => StaAsync(async () =>
    {
        var files = new Files(); var pending = new TaskCompletionSource<HttpResponseMessage>(); CancellationToken captured = default;
        using var runtime = new RuntimeClient(new Handler((_, ct) => { captured = ct; return pending.Task; }), () => Token);
        var window = new MemoryBackupWindow(runtime, files); var operation = window.ExportAsync(); window.Close();
        Assert.True(captured.IsCancellationRequested); pending.SetResult(Response(Encoding.UTF8.GetString(files.Bytes))); await operation;
        Assert.Equal(0, files.Writes); Assert.Empty(window.StatusText.Text);
    });
    [Fact] public async Task BackupResponseAboveOneMiBSucceedsWhileOrdinaryResponseCapStaysUnchanged()
    {
        string backup = Backup(600, "x".PadRight(2000, 'x')); Assert.True(Encoding.UTF8.GetByteCount(backup) > 1024 * 1024);
        using var runtime = Client(backup); var file = await runtime.ExportMemoryAsync(default); Assert.Equal(600, file.Metadata.ItemCount); Safe(file.ToString());
        var failure = await Assert.ThrowsAsync<DesktopException>(() => runtime.ListMemoryAsync(new(), default)); Assert.Equal(DesktopError.InvalidResponse, failure.Error); Safe(failure.ToString());
    }
    [Fact] public void StrictBackupValidationRejectsDuplicateKeysFieldsVersionsAndTamper()
    {
        string valid = Backup(); Assert.Equal(1, RuntimeClient.ValidateBackupFile(Encoding.UTF8.GetBytes(valid)).ItemCount);
        foreach (string bad in new[] { valid[..^1], valid + "{}", valid.Replace("\"format\":", "\"extra\":0,\"format\":"),
            valid.Replace("\"format\":", "\"format\":\"duplicate\",\"format\":"), valid.Replace(PrivateContent, "changed"),
            valid.Replace("\"revision\":3", "\"revision\":0") })
        { var error = Assert.Throws<DesktopException>(() => RuntimeClient.ValidateBackupFile(Encoding.UTF8.GetBytes(bad))); Assert.Equal(DesktopError.MemoryBackupInvalid, error.Error); Safe(error.ToString()); }
        var unsupported = Assert.Throws<DesktopException>(() => RuntimeClient.ValidateBackupFile(Encoding.UTF8.GetBytes(valid.Replace("\"formatVersion\":1", "\"formatVersion\":2"))));
        Assert.Equal(DesktopError.MemoryBackupUnsupported, unsupported.Error);
    }
    [Fact] public async Task NativeFileIoIsBoundedUsesNoUnconfirmedOverwriteAndSuppressesPaths()
    {
        string root = System.IO.Path.Combine(System.IO.Path.GetTempPath(), "workspace-backup-desktop-" + Guid.NewGuid().ToString("N"));
        System.IO.Directory.CreateDirectory(root);
        try
        {
            string file = System.IO.Path.Combine(root, "backup.json"); var files = new NativeMemoryBackupFiles(); byte[] bytes = Encoding.UTF8.GetBytes(Backup());
            await files.WriteAsync(new(file, false), bytes, default); var read = await files.ReadAsync(file, default); Assert.True(bytes.SequenceEqual(read), "File bytes differ.");
            var error = await Assert.ThrowsAsync<DesktopException>(() => files.WriteAsync(new(file, false), Encoding.UTF8.GetBytes("changed"), default));
            Assert.DoesNotContain(root, error.ToString()); read = await files.ReadAsync(file, default); Assert.True(bytes.SequenceEqual(read), "Existing file was overwritten.");
            await files.WriteAsync(new(file, true), bytes, default);
            using (var large = System.IO.File.OpenWrite(file)) large.SetLength(RuntimeClient.MaximumBackupBytes + 1);
            var largeError = await Assert.ThrowsAsync<DesktopException>(() => files.ReadAsync(file, default)); Assert.Equal(DesktopError.MemoryBackupTooLarge, largeError.Error);
            Assert.Single(System.IO.Directory.EnumerateFiles(root));
        }
        finally { System.IO.Directory.Delete(root, true); }
    }
    private static Task StaAsync(Func<Task> action)
    {
        var done = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var thread = new Thread(() =>
        {
            var dispatcher = Dispatcher.CurrentDispatcher;
            SynchronizationContext.SetSynchronizationContext(new DispatcherSynchronizationContext(dispatcher));
            dispatcher.BeginInvoke(new Action(async () =>
            {
                try { await action(); done.SetResult(); } catch (Exception error) { done.SetException(error); }
                finally { dispatcher.InvokeShutdown(); }
            })); Dispatcher.Run();
        }); thread.SetApartmentState(ApartmentState.STA); thread.Start(); return done.Task;
    }
}
