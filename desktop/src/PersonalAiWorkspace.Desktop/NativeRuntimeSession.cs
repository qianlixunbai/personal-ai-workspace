#if MMF3_PROFILE_ACCEPTANCE && DEBUG
using System;
using System.Buffers.Binary;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;

namespace PersonalAiWorkspace.Desktop;

// One private pipe conversation with the Process returned by this desktop's Start.
// No PID lookup, saved READY, HTTP shutdown endpoint or reusable launch token.
internal sealed class NativeRuntimeSession
{
    internal sealed class Failure(string code) : IOException("Native Runtime reported a fixed startup failure.")
    { internal string Code { get; } = code; }
    private readonly Process process;
    internal string Attempt { get; }
    internal string Challenge { get; private set; } = "";
    internal int Port { get; private set; }
    internal bool Closed { get; private set; }
    private bool closeAttempted;
    private NativeRuntimeSession(Process process, string attempt) { this.process = process; Attempt = attempt; }
    internal bool Owns(Process value) => ReferenceEquals(process, value);

    internal static async Task<NativeRuntimeSession> OpenAsync(Process process, string profile, string attempt, CancellationToken token)
    {
        if (!Regex.IsMatch(attempt, "\\A[a-f0-9]{32}\\z")) throw Stop();
        var session = new NativeRuntimeSession(process, attempt);
        if (await session.Read(token) != "WORKSPACE_NATIVE_LIFECYCLE_V1" || await session.Read(token) != profile) throw Stop();
        byte[] pid = new byte[8]; await process.StandardOutput.BaseStream.ReadExactlyAsync(pid, token);
        if (BinaryPrimitives.ReadInt64BigEndian(pid) != process.Id || process.HasExited) throw Stop();
        session.Challenge = await session.Read(token);
        if (!Regex.IsMatch(session.Challenge, "\\A[a-f0-9]{64}\\z")) throw Stop();
        await session.Write(token, attempt, session.Challenge);
        if (await session.Read(token) != "READY" || await session.Read(token) != attempt
            || await session.Read(token) != session.Challenge) throw Stop();
        byte[] port = new byte[4]; await process.StandardOutput.BaseStream.ReadExactlyAsync(port, token);
        session.Port = BinaryPrimitives.ReadInt32BigEndian(port);
        if (session.Port is < 1 or > 65535 or 18765 || process.HasExited) throw Stop();
        return session;
    }

    internal async Task CloseAsync(CancellationToken token)
    {
        if (closeAttempted || Closed || process.HasExited) throw Stop();
        closeAttempted = true;
        await Write(token, "CLOSE", Attempt, Challenge);
        if (await Read(token) != "CONTEXT_CLOSED" || await Read(token) != Attempt || await Read(token) != Challenge) throw Stop();
        process.StandardInput.Close();
        await process.WaitForExitAsync(token);
        if (!process.WaitForExit(1000)) throw Stop(); // Wait on our retained kernel handle as well.
        process.Refresh();
        if (process.ExitCode != 0 || await process.StandardOutput.BaseStream.ReadAsync(new byte[1], token) != 0
            || await process.StandardError.BaseStream.ReadAsync(new byte[1], token) != 0) throw Stop();
        Closed = true;
    }

    private async Task<string> Read(CancellationToken token)
    {
        string value = await ReadValue(token);
        if (value != "STOP") return value;
        var diagnostic = new RuntimeStartupDiagnostic();
        diagnostic.Accept("WORKSPACE_STARTUP_V1\t" + await ReadValue(token));
        throw new Failure(diagnostic.Failure ?? "STARTUP_CAUSE_UNAVAILABLE");
    }
    private async Task<string> ReadValue(CancellationToken token)
    {
        byte[] header = new byte[2]; await process.StandardOutput.BaseStream.ReadExactlyAsync(header, token);
        int size = BinaryPrimitives.ReadUInt16BigEndian(header);
        if (size is < 1 or > 96) throw Stop();
        byte[] bytes = new byte[size]; await process.StandardOutput.BaseStream.ReadExactlyAsync(bytes, token);
        if (bytes.Any(value => value is < 32 or > 126)) throw Stop();
        return Encoding.ASCII.GetString(bytes);
    }
    private async Task Write(CancellationToken token, params string[] fields)
    {
        foreach (string field in fields)
        {
            byte[] bytes = Encoding.ASCII.GetBytes(field), header = new byte[2];
            BinaryPrimitives.WriteUInt16BigEndian(header, checked((ushort)bytes.Length));
            await process.StandardInput.BaseStream.WriteAsync(header, token);
            await process.StandardInput.BaseStream.WriteAsync(bytes, token);
        }
        await process.StandardInput.BaseStream.FlushAsync(token);
    }
    private static IOException Stop() => new("Native Runtime lifecycle incomplete; preserve launch history.");
}
#endif
