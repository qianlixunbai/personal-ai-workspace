using System;
using System.Runtime.InteropServices;
using System.Windows.Interop;

namespace PersonalAiWorkspace.Desktop;

internal interface IHotkeyApi
{
    bool Register(IntPtr window, int id);
    void Unregister(IntPtr window, int id);
}
internal sealed class HotkeyApi : IHotkeyApi
{
    public bool Register(IntPtr window, int id) => Native.RegisterHotKey(window, id, 0x4000 | 0x1 | 0x2 | 0x4,
        id == HotkeyRegistration.QuickActionsId ? 0x51u : 0x54u);
    public void Unregister(IntPtr window, int id) => Native.UnregisterHotKey(window, id);
}
internal sealed class HotkeyRegistration : IDisposable
{
    internal const int Id = 0x50A1;
    internal const int QuickActionsId = 0x50A2;
    private readonly IHotkeyApi api;
    private readonly IntPtr window;
    private readonly int id;
    private bool registered;
    internal HotkeyRegistration(IntPtr window, IHotkeyApi? api = null, int id = Id)
    {
        this.window = window;
        this.id = id;
        this.api = api ?? new HotkeyApi();
        registered = this.api.Register(window, id);
        if (!registered) throw new InvalidOperationException((id == QuickActionsId ? "Ctrl+Alt+Shift+Q" : "Ctrl+Alt+Shift+T")
            + " 注册失败：快捷键可能已被占用。可从托盘打开 Quick Assistant 手动输入。");
    }
    public void Dispose()
    {
        if (registered) { api.Unregister(window, id); registered = false; }
    }
}
