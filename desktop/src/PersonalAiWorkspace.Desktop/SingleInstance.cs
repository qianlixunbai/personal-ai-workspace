using System;
using System.Security.Principal;
using System.Threading;

namespace PersonalAiWorkspace.Desktop;

internal sealed class SingleInstance : IDisposable
{
    private readonly Mutex mutex;
    private EventWaitHandle? activation;
    private RegisteredWaitHandle? listener;
    private bool owns;
    private readonly string name;
    internal SingleInstance(string? testSuffix = null)
    {
        name = @"Local\PersonalAiWorkspace.Desktop." + WindowsIdentity.GetCurrent().User!.Value + (testSuffix ?? "");
        mutex = new Mutex(false, name);
        try { owns = mutex.WaitOne(0); }
        catch (AbandonedMutexException) { owns = true; }
        if (owns) activation = new EventWaitHandle(false, EventResetMode.AutoReset, name + ".Open");
    }
    internal bool IsPrimary => owns;
    internal void Listen(Action activate)
    {
        if (activation is null) throw new InvalidOperationException("Primary instance required.");
        listener = ThreadPool.RegisterWaitForSingleObject(activation, (_, timedOut) => { if (!timedOut) activate(); }, null, Timeout.Infinite, false);
    }
    internal bool SignalPrimary()
    {
        try { using var signal = EventWaitHandle.OpenExisting(name + ".Open"); return signal.Set(); }
        catch (WaitHandleCannotBeOpenedException) { return false; }
    }
    internal void StopListening()
    {
        listener?.Unregister(null);
        listener = null;
    }
    public void Dispose()
    {
        StopListening();
        activation?.Dispose();
        activation = null;
        if (owns) { mutex.ReleaseMutex(); owns = false; }
        mutex.Dispose();
    }
}
