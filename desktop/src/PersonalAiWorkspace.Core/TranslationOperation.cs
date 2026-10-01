namespace PersonalAiWorkspace.Core;

public sealed class TranslationOperation(RuntimeClient client, TimeSpan? pollingInterval = null)
{
    private readonly TaskCompletionSource cancelSignal = new(TaskCreationOptions.RunContinuationsAsynchronously);
    private int cancelRequested;
    public void RequestCancel()
    {
        Interlocked.Exchange(ref cancelRequested, 1);
        cancelSignal.TrySetResult();
    }

    public async Task<RuntimeTask> RunAsync(TranslateInput input, Action<RuntimeTask> progress, CancellationToken lifetime)
    {
        Guid? id = null;
        bool terminal = false;
        try
        {
            // User cancellation does not abort POST: first obtain identity, then cancel that task.
            var task = await client.SubmitAsync(input, lifetime);
            id = task.TaskId;
            var started = System.Diagnostics.Stopwatch.StartNew();
            while (true)
            {
                progress(task);
                if (task.Terminal) { terminal = true; return task; }
                if (Volatile.Read(ref cancelRequested) != 0)
                {
                    task = await client.CancelAsync(id.Value, lifetime);
                    if (!task.Terminal) throw new DesktopException(DesktopError.InvalidResponse);
                    continue;
                }
                if (started.Elapsed > TimeSpan.FromSeconds(190)) throw new DesktopException(DesktopError.ClientTimeout);
                await Task.WhenAny(Task.Delay(pollingInterval ?? TimeSpan.FromMilliseconds(300), lifetime), cancelSignal.Task);
                lifetime.ThrowIfCancellationRequested();
                if (Volatile.Read(ref cancelRequested) == 0) task = await client.GetAsync(id.Value, lifetime);
            }
        }
        finally
        {
            if (id.HasValue && !terminal)
            {
                // Best effort on shutdown/communication failure; never claim confirmed cancellation.
                using var cleanup = new CancellationTokenSource(TimeSpan.FromSeconds(2));
                try { await client.CancelAsync(id.Value, cleanup.Token); }
                catch (Exception error) when (error is DesktopException or OperationCanceledException) { }
            }
        }
    }
}
