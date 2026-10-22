#if MMF3_PROFILE_ACCEPTANCE && DEBUG
using System;
using System.Diagnostics;
using System.IO;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;

namespace PersonalAiWorkspace.Desktop;

internal sealed record FinanceIdentity(long UserId, string DatasetFingerprint, string UserFingerprint);
internal sealed record FinanceCandidate(FinanceIdentity Identity, string Username, string Email, string CreatedAt);
internal sealed record FinanceReview(string Database, string Server, string DatasetFingerprint, FinanceCandidate[] Candidates);

// Holds the admitted direct child and its reviewed snapshot until the native user's explicit confirmation.
internal sealed class NativeFinanceSetup : IAsyncDisposable
{
    private readonly RuntimeProcessJob job = RuntimeProcessJob.Create();
    private readonly CancellationTokenSource deadline;
    private readonly TrustedDevelopmentRuntimeSources sources;
    private Process? child;
    private Task errors = Task.CompletedTask;
    private int disposed;
    internal FinanceReview? Review { get; private set; }
    internal NativeFinanceSetup(CancellationToken cancellation)
    {
        deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
        deadline.CancelAfter(TimeSpan.FromMinutes(5));
        try { sources = TrustedDevelopmentRuntimeSources.Open(); }
        catch { deadline.Dispose(); job.Dispose(); throw; }
    }
    internal async Task ReadAsync(Mmf3AcceptanceLaunch profile, byte[]? configuration)
    {
        if (child is not null || (configuration?.Length ?? 0) > 16384) throw new IOException();
        child = Process.Start(sources.StartInfo(profile, financeSetup: true)) ?? throw new IOException();
        job.Assign(child);
        child.StandardInput.NewLine = "\n"; // Java's bounded protocol accepts LF only, including on Windows.
        errors = DrainAsync(child.StandardError);
        if (await ReadLineAsync() != "ADMITTED") throw new IOException();
        await child.StandardInput.WriteLineAsync(configuration is null ? "-" : Convert.ToBase64String(configuration));
        await child.StandardInput.FlushAsync(deadline.Token);
        string result = await ReadLineAsync();
        if (!result.StartsWith("REVIEW\t", StringComparison.Ordinal))
            throw new IOException(result is "REJECTED_CONFIGURATION" or "REJECTED_CONTEXT" or "REJECTED_REVIEW" ? result : "REJECTED_PROTOCOL");
        var bytes = Convert.FromBase64String(result[7..]);
        Review = JsonSerializer.Deserialize<FinanceReview>(bytes, new JsonSerializerOptions(JsonSerializerDefaults.Web)
            { UnmappedMemberHandling = System.Text.Json.Serialization.JsonUnmappedMemberHandling.Disallow }) ?? throw new IOException();
        if (Review.Candidates.Length > 100 || !Fingerprint(Review.DatasetFingerprint)
            || !System.Text.RegularExpressions.Regex.IsMatch(Review.Database, "\\A[a-z][a-z0-9_]{0,62}\\z")) throw new IOException();
        foreach (var candidate in Review.Candidates)
            if (candidate.Identity.UserId < 1 || candidate.Identity.DatasetFingerprint != Review.DatasetFingerprint
                || !Fingerprint(candidate.Identity.UserFingerprint) || candidate.Username.Length > 256 || candidate.Email.Length > 320
                || candidate.CreatedAt.Length > 64) throw new IOException();
    }
    internal async Task ConfirmAsync(int index)
    {
        if (child is null || Review is null || index < 0 || index >= Review.Candidates.Length) throw new IOException();
        await child.StandardInput.WriteLineAsync("CONFIRM " + index.ToString(System.Globalization.CultureInfo.InvariantCulture));
        child.StandardInput.Close();
        if (await ReadLineAsync() != "BOUND") throw new IOException();
        await child.WaitForExitAsync(deadline.Token); await errors;
        if (child.ExitCode != 0 || await child.StandardOutput.ReadAsync(new char[1], deadline.Token) != 0) throw new IOException();
    }
    private static bool Fingerprint(string value) => System.Text.RegularExpressions.Regex.IsMatch(value, "\\A[a-f0-9]{64}\\z");
    private async Task<string> ReadLineAsync()
    {
        var line = new StringBuilder(); var character = new char[1];
        while (await child!.StandardOutput.ReadAsync(character, deadline.Token) != 0)
        {
            if (character[0] == '\n')
            {
                const string prefix = "WORKSPACE_FINANCE_V1\t";
                string value = line.ToString().TrimEnd('\r');
                if (!value.StartsWith(prefix, StringComparison.Ordinal)) throw new IOException();
                return value[prefix.Length..];
            }
            if (line.Length >= 131072) throw new IOException();
            line.Append(character[0]);
        }
        throw new IOException();
    }
    private async Task DrainAsync(StreamReader reader)
    {
        var buffer = new char[1024]; int total = 0, count;
        while ((count = await reader.ReadAsync(buffer, deadline.Token)) != 0)
            if ((total += count) > 1024 * 1024) throw new IOException();
    }
    public async ValueTask DisposeAsync()
    {
        if (Interlocked.Exchange(ref disposed, 1) != 0) return;
        deadline.Cancel(); job.Dispose();
        try { if (child is not null) { if (!child.HasExited) child.Kill(); await child.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(10)); } }
        finally { child?.Dispose(); try { await errors; } catch (Exception) { } sources.Dispose(); deadline.Dispose(); }
    }
}
#endif
