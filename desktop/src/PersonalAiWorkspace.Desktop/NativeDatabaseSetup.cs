#if MMF3_PROFILE_ACCEPTANCE && DEBUG
using System;
using System.Diagnostics;
using System.IO;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace PersonalAiWorkspace.Desktop;

internal enum DatabaseSetupResult { CONFIGURED, NOT_CONFIGURED, READY, SAVED, ALREADY_CONFIGURED,
    ADMISSION_DENIED, CONFIGURATION_UNTRUSTED, CREDENTIAL_UNAVAILABLE, CONNECTION_FAILED, AUTHENTICATION_FAILED,
    SCHEMA_INITIALIZATION_REQUIRED, SCHEMA_INCOMPATIBLE, ROLE_REJECTED, POSTGRESQL_NOT_CONFIGURED }

// Uses the same pinned Runtime/JDK and original Native Gate, with no port or file transport.
internal static class NativeDatabaseSetup
{
    internal static async Task<DatabaseSetupResult> ExecuteAsync(Mmf3AcceptanceLaunch profile, string action,
        byte[]? configuration, CancellationToken cancellation)
    {
        if (action is not ("STATE" or "CHECK" or "SAVE!") || (configuration?.Length ?? 0) > 16384)
            return DatabaseSetupResult.CONFIGURATION_UNTRUSTED;
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
        deadline.CancelAfter(TimeSpan.FromSeconds(45));
        Process? child = null;
        TrustedDevelopmentRuntimeSources? sources = null;
        Task errors = Task.CompletedTask;
        using var job = RuntimeProcessJob.Create();
        try
        {
            sources = TrustedDevelopmentRuntimeSources.Open();
            deadline.Token.ThrowIfCancellationRequested();
            child = Process.Start(sources.StartInfo(profile, databaseSetup: true)) ?? throw new IOException();
            job.Assign(child);
            errors = DrainAsync(child.StandardError, deadline.Token);
            string code = await ReadCodeAsync(child.StandardOutput, deadline.Token);
            bool admitted = code == "ADMITTED";
            if (admitted)
            {
                // No secrets until admission, and never a command line, environment variable or WebMessage.
                await child.StandardInput.BaseStream.WriteAsync(Encoding.ASCII.GetBytes(action + "\n"), deadline.Token);
                if (configuration is not null) await child.StandardInput.BaseStream.WriteAsync(configuration, deadline.Token);
                child.StandardInput.Close();
                code = await ReadCodeAsync(child.StandardOutput, deadline.Token);
            }
            await child.WaitForExitAsync(deadline.Token);
            await errors;
            if (await child.StandardOutput.ReadAsync(new char[1], deadline.Token) != 0
                || !Enum.TryParse<DatabaseSetupResult>(code, out var result) || result.ToString() != code
                || !Enum.IsDefined(result)) throw new IOException();
            bool success = result is DatabaseSetupResult.CONFIGURED or DatabaseSetupResult.NOT_CONFIGURED
                or DatabaseSetupResult.READY or DatabaseSetupResult.SAVED or DatabaseSetupResult.ALREADY_CONFIGURED;
            if (success && (!admitted || !(action switch
                {
                    "STATE" => result is DatabaseSetupResult.CONFIGURED or DatabaseSetupResult.NOT_CONFIGURED,
                    "CHECK" => result == DatabaseSetupResult.READY,
                    "SAVE!" => result is DatabaseSetupResult.SAVED or DatabaseSetupResult.ALREADY_CONFIGURED,
                    _ => false
                }))) throw new IOException();
            if (child.ExitCode != (success ? 0 : 10)) throw new IOException();
            return result;
        }
        catch (OperationCanceledException) when (cancellation.IsCancellationRequested) { throw; }
        catch (Exception) { return DatabaseSetupResult.ADMISSION_DENIED; }
        finally
        {
            job.Dispose();
            try
            {
                if (child is not null)
                {
                    try
                    {
                        if (!child.HasExited) child.Kill();
                        using var cleanup = new CancellationTokenSource(TimeSpan.FromSeconds(10));
                        await child.WaitForExitAsync(cleanup.Token);
                    }
                    finally { child.Dispose(); }
                }
            }
            finally
            {
                try { await errors.WaitAsync(TimeSpan.FromSeconds(2)); } catch (Exception) { /* Raw stderr is discarded. */ }
                sources?.Dispose();
            }
        }
    }
    private static async Task<string> ReadCodeAsync(StreamReader reader, CancellationToken cancellation)
    {
        var line = new StringBuilder(128); var character = new char[1];
        while (await reader.ReadAsync(character, cancellation) != 0)
        {
            if (character[0] == '\n')
            {
                string value = line.ToString().TrimEnd('\r');
                const string prefix = "WORKSPACE_DATABASE_V1\t";
                if (!value.StartsWith(prefix, StringComparison.Ordinal)) throw new IOException();
                return value[prefix.Length..];
            }
            if (line.Length == 128) throw new IOException();
            line.Append(character[0]);
        }
        throw new IOException();
    }
    private static async Task DrainAsync(StreamReader reader, CancellationToken cancellation)
    {
        var buffer = new char[1024]; int total = 0, count;
        while ((count = await reader.ReadAsync(buffer, cancellation)) != 0)
            if ((total += count) > 1024 * 1024) throw new IOException();
    }
}
#endif
