using System.Buffers.Binary;
using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace PersonalAiWorkspace.Core;

public sealed record MemoryBackupMetadata(int FormatVersion, int SchemaVersion, int ItemCount, string ContentDigest);
public sealed class MemoryBackupFile(byte[] bytes, MemoryBackupMetadata metadata)
{
    public ReadOnlyMemory<byte> Bytes { get; } = bytes;
    public MemoryBackupMetadata Metadata { get; } = metadata;
    public override string ToString() => $"MemoryBackupFile[itemCount={Metadata.ItemCount}]";
}

public sealed partial class RuntimeClient
{
    public const int MaximumBackupBytes = 1000 * (2000 * 6 + 160 * 12 + 1024) + 4096;
    public const int MaximumRestoreBytes = MaximumBackupBytes + 64 * 1024;
    public async Task<MemoryBackupFile> ExportMemoryAsync(CancellationToken token)
    {
        using var body = await SendAsync(HttpMethod.Get, "/api/v1/memory/backup", null, true, HttpStatusCode.OK, token,
            endpointErrorMap: BackupError, maximumResponse: MaximumBackupBytes);
        byte[] bytes = Encoding.UTF8.GetBytes(body.RootElement.GetRawText());
        return new MemoryBackupFile(bytes, ValidateBackup(body.RootElement));
    }
    public async Task<MemoryBackupMetadata> RestoreMemoryBackupAsync(byte[] bytes, string targetDirectory, CancellationToken token)
    {
        var metadata = ValidateBackupFile(bytes);
        if (!Path.IsPathFullyQualified(targetDirectory) || targetDirectory.Length > 8192)
            throw new DesktopException(DesktopError.MemoryRestoreFailed);
        using var buffer = new MemoryStream();
        using (var writer = new Utf8JsonWriter(buffer))
        {
            writer.WriteStartObject(); writer.WriteString("targetDirectory", targetDirectory);
            writer.WritePropertyName("backup"); writer.WriteRawValue(bytes); writer.WriteEndObject();
        }
        if (buffer.Length > MaximumRestoreBytes) throw new DesktopException(DesktopError.MemoryBackupTooLarge);
        using var body = await SendAsync(HttpMethod.Post, "/api/v1/memory/backup/restore", buffer.ToArray(), true, HttpStatusCode.OK, token,
            endpointErrorMap: BackupError);
        var actual = ParseBackupMetadata(body.RootElement);
        if (actual != metadata) throw Invalid();
        return actual;
    }
    public static MemoryBackupMetadata ValidateBackupFile(byte[] bytes)
    {
        if (bytes.Length > MaximumBackupBytes) throw new DesktopException(DesktopError.MemoryBackupTooLarge);
        try
        {
            using var body = JsonDocument.Parse(bytes, new JsonDocumentOptions { MaxDepth = 24 });
            RejectDuplicates(body.RootElement);
            return ValidateBackup(body.RootElement);
        }
        catch (DesktopException error) when (error.Error is DesktopError.MemoryBackupUnsupported) { throw; }
        catch (Exception) { throw new DesktopException(DesktopError.MemoryBackupInvalid); }
    }
    private static MemoryBackupMetadata ValidateBackup(JsonElement root)
    {
        RejectDuplicates(root);
        MemoryFields(root, "format", "formatVersion", "schemaVersion", "createdAt", "itemCount", "contentDigest", "items");
        if (String(root, "format") != "personal-ai-workspace.memory-backup") throw Invalid();
        if (MemoryInteger(root, "formatVersion") != 1 || MemoryInteger(root, "schemaVersion") != 1)
            throw new DesktopException(DesktopError.MemoryBackupUnsupported);
        _ = MemoryTime(String(root, "createdAt"));
        var array = Property(root, "items"); int count = MemoryInteger(root, "itemCount");
        if (count < 0 || count > 1000 || array.ValueKind != JsonValueKind.Array || array.GetArrayLength() != count) throw Invalid();
        var rows = array.EnumerateArray().OrderBy(row => String(row, "id"), StringComparer.Ordinal).ToArray();
        var ids = new HashSet<Guid>();
        using var hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        void Add(string text)
        {
            byte[] utf8 = new UTF8Encoding(false, true).GetBytes(text); Span<byte> length = stackalloc byte[4];
            BinaryPrimitives.WriteInt32BigEndian(length, utf8.Length); hash.AppendData(length); hash.AppendData(utf8);
        }
        foreach (string field in new[] { "format", "formatVersion", "schemaVersion", "createdAt", "itemCount" })
            Add(Property(root, field).ValueKind == JsonValueKind.String ? String(root, field) : Property(root, field).GetRawText());
        foreach (var row in rows)
        {
            var item = ParseMemory(row, null);
            if (!ids.Add(item.Id) || String(row, "id") != item.Id.ToString("D")) throw Invalid();
            foreach (string field in new[] { "id", "type", "title", "content", "status", "revision", "source", "createdAt", "updatedAt" })
                Add(field == "revision" ? item.Revision.ToString(System.Globalization.CultureInfo.InvariantCulture) : String(row, field));
        }
        string digest = String(root, "contentDigest");
        if (digest.Length != 64 || digest != Convert.ToHexStringLower(hash.GetHashAndReset())) throw Invalid();
        return new(1, 1, count, digest);
    }
    private static MemoryBackupMetadata ParseBackupMetadata(JsonElement root)
    {
        MemoryFields(root, "formatVersion", "schemaVersion", "itemCount", "contentDigest");
        int count = MemoryInteger(root, "itemCount"); string digest = String(root, "contentDigest");
        if (MemoryInteger(root, "formatVersion") != 1 || MemoryInteger(root, "schemaVersion") != 1 || count < 0 || count > 1000
            || !System.Text.RegularExpressions.Regex.IsMatch(digest, "\\A[0-9a-f]{64}\\z")) throw Invalid();
        return new(1, 1, count, digest);
    }
    private static DesktopError BackupError(HttpStatusCode status, JsonElement root)
    {
        MemoryFields(root, "code", "message", "phase"); _ = String(root, "message"); _ = String(root, "phase");
        return ((int)status, String(root, "code")) switch
        {
            (400, "MEMORY_BACKUP_INVALID") => DesktopError.MemoryBackupInvalid,
            (400, "MEMORY_BACKUP_UNSUPPORTED") => DesktopError.MemoryBackupUnsupported,
            (413, "MEMORY_BACKUP_TOO_LARGE") => DesktopError.MemoryBackupTooLarge,
            (409, "MEMORY_RESTORE_TARGET_NOT_EMPTY") => DesktopError.MemoryRestoreTargetNotEmpty,
            (500, "MEMORY_EXPORT_FAILED") => DesktopError.MemoryExportFailed,
            (500, "MEMORY_RESTORE_FAILED") => DesktopError.MemoryRestoreFailed,
            _ => MemoryError(status, root)
        };
    }
}
