using System;
using System.Runtime.InteropServices;

namespace PersonalAiWorkspace.Desktop;

internal static class Native
{
    [DllImport("user32.dll")] internal static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] internal static extern uint GetWindowThreadProcessId(IntPtr window, out uint processId);
    [DllImport("user32.dll")] internal static extern bool GetGUIThreadInfo(uint thread, ref GuiThreadInfo info);
    [DllImport("user32.dll")] internal static extern short GetAsyncKeyState(int key);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern int GetClassName(IntPtr window, System.Text.StringBuilder name, int count);
    [DllImport("user32.dll", EntryPoint = "GetWindowLongW")] private static extern int GetWindowLong(IntPtr window, int index);
    [DllImport("user32.dll", SetLastError = true)] internal static extern bool RegisterHotKey(IntPtr window, int id, uint modifiers, uint key);
    [DllImport("user32.dll", SetLastError = true)] internal static extern bool UnregisterHotKey(IntPtr window, int id);
    [DllImport("user32.dll", SetLastError = true)] internal static extern uint SendInput(uint count, Input[] inputs, int size);
    [DllImport("user32.dll")] internal static extern uint GetClipboardSequenceNumber();
    [DllImport("user32.dll")] internal static extern IntPtr GetClipboardOwner();
    [DllImport("user32.dll")] internal static extern bool OpenClipboard(IntPtr window);
    [DllImport("user32.dll")] internal static extern bool CloseClipboard();
    [DllImport("user32.dll", SetLastError = true)] internal static extern uint EnumClipboardFormats(uint format);
    [DllImport("user32.dll")] internal static extern IntPtr GetClipboardData(uint format);
    [DllImport("user32.dll")] internal static extern IntPtr SetClipboardData(uint format, IntPtr data);
    [DllImport("user32.dll")] internal static extern bool EmptyClipboard();
    [DllImport("kernel32.dll")] internal static extern IntPtr GlobalAlloc(uint flags, UIntPtr bytes);
    [DllImport("kernel32.dll")] internal static extern IntPtr GlobalLock(IntPtr handle);
    [DllImport("kernel32.dll")] internal static extern bool GlobalUnlock(IntPtr handle);
    [DllImport("kernel32.dll")] internal static extern UIntPtr GlobalSize(IntPtr handle);
    [DllImport("kernel32.dll")] internal static extern IntPtr GlobalFree(IntPtr handle);

    [StructLayout(LayoutKind.Sequential)] internal struct GuiThreadInfo
    {
        internal int Size;
        internal uint Flags;
        internal IntPtr Active, Focus, Capture, MenuOwner, MoveSize, Caret;
        internal int Left, Top, Right, Bottom;
    }
    // INPUT must include the full native union size on both x64 and x86.
    [StructLayout(LayoutKind.Sequential)] internal struct Input { internal uint Type; internal InputUnion Data; }
    [StructLayout(LayoutKind.Explicit)] internal struct InputUnion
    {
        [FieldOffset(0)] internal KeyboardInput Keyboard;
        [FieldOffset(0)] internal MouseInput Mouse;
    }
    [StructLayout(LayoutKind.Sequential)] internal struct KeyboardInput
    { internal ushort Key, Scan; internal uint Flags, Time; internal UIntPtr Extra; }
    [StructLayout(LayoutKind.Sequential)] internal struct MouseInput
    { internal int X, Y; internal uint Data, Flags, Time; internal UIntPtr Extra; }

    internal static IntPtr FocusWindow(IntPtr foreground)
    {
        uint thread = GetWindowThreadProcessId(foreground, out _);
        var info = new GuiThreadInfo { Size = Marshal.SizeOf<GuiThreadInfo>() };
        return thread != 0 && GetGUIThreadInfo(thread, ref info) ? info.Focus : IntPtr.Zero;
    }
    internal static bool Stable(IntPtr foreground, IntPtr focus) => foreground != IntPtr.Zero && focus != IntPtr.Zero
        && GetForegroundWindow() == foreground && FocusWindow(foreground) == focus;
    internal static bool SafeNativeCopyTarget(IntPtr focus)
    {
        var name = new System.Text.StringBuilder(128);
        if (GetClassName(focus, name, name.Capacity) == 0) return false;
        // Only native edit controls have stable focus identity for this conservative fallback.
        return name.ToString() is "Edit" or "RichEdit20W" or "RICHEDIT50W" && (GetWindowLong(focus, -16) & 0x20) == 0;
    }
}
