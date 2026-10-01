using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using System.Globalization;
using System.Threading;
using System.Threading.Tasks;
using PersonalAiWorkspace.Core;

namespace PersonalAiWorkspace.Desktop;

internal sealed class NativeCopyPort(IntPtr foreground, IntPtr focus, IntPtr ownerWindow) : ICopyPort
{
    public bool ContextStable => Native.Stable(foreground, focus) && Native.SafeNativeCopyTarget(focus);
    public bool ModifiersReleased
    {
        get
        {
            foreach (int key in new[] { 0x10, 0x11, 0x12, 0x5B, 0x5C, 0x54 })
                if ((Native.GetAsyncKeyState(key) & 0x8000) != 0) return false;
            return true;
        }
    }
    public uint Sequence => Native.GetClipboardSequenceNumber();
    public async Task<ClipboardSnapshot?> SnapshotAsync(CancellationToken cancellationToken)
    {
        try
        {
            return await HelperProcess.RunAsync<ClipboardSnapshot>("--clipboard-snapshot", Arguments(),
                512 * 1024, TimeSpan.FromSeconds(1.5), cancellationToken);
        }
        catch (OperationCanceledException) { throw; }
        catch (Exception) { return null; }
    }
    private ClipboardSnapshot? SnapshotDirect()
    {
        if (!ContextStable || !Native.OpenClipboard(ownerWindow)) return null;
        try
        {
            uint sequence = Sequence;
            var formats = new HashSet<uint>();
            uint format = 0;
            while ((format = Native.EnumClipboardFormats(format)) != 0)
            {
                formats.Add(format);
                if (formats.Count > 16) return new(sequence, null, false);
            }
            if (Marshal.GetLastWin32Error() != 0) return new(sequence, null, false);
            if (formats.Count == 0) return new(sequence, null, true);
            // Refuse images, rich text, files, app-specific and delayed non-text formats.
            foreach (uint value in formats)
                if (value is not (1 or 7 or 13 or 16)) return new(sequence, null, false);
            string? text = ReadUnicode(65536);
            return new(sequence, text, text is not null);
        }
        finally { Native.CloseClipboard(); }
    }
    public bool SendCopy(uint expectedSequence)
    {
        if (!ContextStable || !ModifiersReleased || Sequence != expectedSequence) return false;
        var inputs = new[] { Key(0x11, false), Key(0x43, false), Key(0x43, true), Key(0x11, true) };
        uint sent = Native.SendInput(4, inputs, Marshal.SizeOf<Native.Input>());
        if (sent != 4)
        {
            // Release only keys this one Copy action might have pressed; no keyboard hook.
            Native.SendInput(2, new[] { Key(0x43, true), Key(0x11, true) }, Marshal.SizeOf<Native.Input>());
            return sent != 0;
        }
        return true;
    }
    public bool ExpectedCopy(uint before, uint observed)
    {
        Native.GetWindowThreadProcessId(foreground, out uint foregroundProcess);
        Native.GetWindowThreadProcessId(Native.GetClipboardOwner(), out uint clipboardProcess);
        return SelectionRules.FreshClipboard(before, observed, Sequence, foregroundProcess != 0 && foregroundProcess == clipboardProcess, ContextStable);
    }
    public async Task<string?> ReadFreshAsync(uint before, uint observed, CancellationToken cancellationToken)
    {
        try
        {
            return await HelperProcess.RunAsync<string>("--clipboard-read", Arguments(before, observed),
                512 * 1024, TimeSpan.FromSeconds(1.5), cancellationToken);
        }
        catch (OperationCanceledException) { throw; }
        catch (Exception) { return null; }
    }
    private string? ReadFreshDirect(uint before, uint observed)
    {
        if (!Native.OpenClipboard(ownerWindow)) return null;
        try
        {
            if (!ExpectedCopy(before, observed)) return null;
            return ReadUnicode(65536);
        }
        finally { Native.CloseClipboard(); }
    }
    public async Task<ClipboardRestore> RestoreAsync(ClipboardSnapshot snapshot, uint copiedSequence)
    {
        try
        {
            // Separate cleanup deadline: user/app cancellation cannot skip attempted restoration.
            return await HelperProcess.RunAsync<ClipboardRestore>("--clipboard-restore", Arguments(copiedSequence),
                128, TimeSpan.FromSeconds(1.5), CancellationToken.None, snapshot);
        }
        catch (Exception) { return ClipboardRestore.Failed; }
    }
    private ClipboardRestore RestoreDirect(ClipboardSnapshot snapshot, uint copiedSequence)
    {
        if (!Native.OpenClipboard(ownerWindow)) return ClipboardRestore.Failed;
        IntPtr allocation = IntPtr.Zero;
        try
        {
            Native.GetWindowThreadProcessId(foreground, out uint foregroundProcess);
            Native.GetWindowThreadProcessId(Native.GetClipboardOwner(), out uint clipboardProcess);
            if (Sequence != copiedSequence || foregroundProcess == 0 || foregroundProcess != clipboardProcess)
                return ClipboardRestore.ExternalChange;
            if (snapshot.Text is not null)
            {
                byte[] data = Encoding.Unicode.GetBytes(snapshot.Text + '\0');
                allocation = Native.GlobalAlloc(0x42, new UIntPtr((uint)data.Length));
                if (allocation == IntPtr.Zero) return ClipboardRestore.Failed;
                IntPtr memory = Native.GlobalLock(allocation);
                if (memory == IntPtr.Zero) return ClipboardRestore.Failed;
                try { Marshal.Copy(data, 0, memory, data.Length); }
                finally { Native.GlobalUnlock(allocation); }
            }
            if (!Native.EmptyClipboard()) return ClipboardRestore.Failed;
            if (allocation != IntPtr.Zero)
            {
                if (Native.SetClipboardData(13, allocation) == IntPtr.Zero) return ClipboardRestore.Failed;
                allocation = IntPtr.Zero; // OS owns the handle now.
            }
            return ClipboardRestore.Restored;
        }
        finally
        {
            if (allocation != IntPtr.Zero) Native.GlobalFree(allocation);
            Native.CloseClipboard();
        }
    }
    private static string? ReadUnicode(int maximumCharacters)
    {
        IntPtr handle = Native.GetClipboardData(13);
        if (handle == IntPtr.Zero) return null;
        ulong bytes = Native.GlobalSize(handle).ToUInt64();
        if (bytes < 2 || bytes > (ulong)(maximumCharacters + 1) * 2 || bytes % 2 != 0) return null;
        IntPtr memory = Native.GlobalLock(handle);
        if (memory == IntPtr.Zero) return null;
        try
        {
            int characters = (int)bytes / 2;
            for (int index = 0; index < characters; index++)
                if (Marshal.ReadInt16(memory, index * 2) == 0) return Marshal.PtrToStringUni(memory, index);
            return null;
        }
        finally { Native.GlobalUnlock(handle); }
    }
    private static Native.Input Key(ushort key, bool up) => new()
    {
        Type = 1, Data = new Native.InputUnion { Keyboard = new Native.KeyboardInput { Key = key, Flags = up ? 2u : 0u } }
    };
    private string[] Arguments(params uint[] sequences)
    {
        var args = new List<string> { foreground.ToInt64().ToString(CultureInfo.InvariantCulture), focus.ToInt64().ToString(CultureInfo.InvariantCulture) };
        foreach (uint value in sequences) args.Add(value.ToString(CultureInfo.InvariantCulture));
        return args.ToArray();
    }
    internal static int RunWorker(string mode, string[] args)
    {
        try
        {
            if (args.Length < 3 || !long.TryParse(args[1], out long foreground) || !long.TryParse(args[2], out long focus)) return 2;
            // Clipboard helpers use the STA entry thread; none own a visible window.
            using var owner = new System.Windows.Interop.HwndSource(new System.Windows.Interop.HwndSourceParameters("Clipboard helper") { ParentWindow = new IntPtr(-3) });
            var port = new NativeCopyPort(new IntPtr(foreground), new IntPtr(focus), owner.Handle);
            object? result;
            if (mode == "--clipboard-snapshot" && args.Length == 3) result = port.SnapshotDirect();
            else if (mode == "--clipboard-read" && args.Length == 5 && uint.TryParse(args[3], out uint before) && uint.TryParse(args[4], out uint observed))
                result = port.ReadFreshDirect(before, observed);
            else if (mode == "--clipboard-restore" && args.Length == 4 && uint.TryParse(args[3], out uint copied))
            {
                using var input = Console.OpenStandardInput();
                using var buffer = new System.IO.MemoryStream();
                var chunk = new byte[4096];
                int count;
                while ((count = input.Read(chunk, 0, chunk.Length)) > 0)
                {
                    if (buffer.Length + count > 512 * 1024) return 2;
                    buffer.Write(chunk, 0, count);
                }
                var snapshot = JsonSerializer.Deserialize<ClipboardSnapshot>(buffer.ToArray());
                if (snapshot is null || !snapshot.Supported || snapshot.Text?.Length > 65536) return 2;
                result = port.RestoreDirect(snapshot, copied);
            }
            else return 2;
            using var output = Console.OpenStandardOutput();
            JsonSerializer.Serialize(output, result);
            return 0;
        }
        catch (Exception) { return 1; }
    }
}
