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
    public bool Register(IntPtr window, int id) => Native.RegisterHotKey(window, id, 0x4000 | 0x1 | 0x2 | 0x4, 0x54);
    public void Unregister(IntPtr window, int id) => Native.UnregisterHotKey(window, id);
}
internal sealed class HotkeyRegistration : IDisposable
{
    internal const int Id = 0x50A1;
    private readonly IHotkeyApi api;
    private readonly IntPtr window;
    private bool registered;
    internal HotkeyRegistration(IntPtr window, IHotkeyApi? api = null)
    {
        this.window = window;
        this.api = api ?? new HotkeyApi();
        registered = this.api.Register(window, Id);
        if (!registered) throw new InvalidOperationException("Ctrl+Alt+Shift+T 注册失败：快捷键可能已被占用。可从托盘打开窗口手动翻译。");
    }
    public void Dispose()
    {
        if (registered) { api.Unregister(window, Id); registered = false; }
    }
}
