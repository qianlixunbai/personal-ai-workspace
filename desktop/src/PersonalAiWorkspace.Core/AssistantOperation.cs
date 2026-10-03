namespace PersonalAiWorkspace.Core;

public sealed class AssistantOperation(RuntimeClient client, TimeSpan? pollingInterval = null)
{
    private readonly TaskCompletionSource cancelSignal = new(TaskCreationOptions.RunContinuationsAsynchronously);
    private int cancelRequested;
    public void RequestCancel()
    {
        Interlocked.Exchange(ref cancelRequested, 1);
        cancelSignal.TrySetResult();
    }

    public bool Accepted { get; private set; }
    public async Task<RuntimeTask> RunAsync(AssistantInput input, Action<RuntimeTask> progress, CancellationToken lifetime,
        IReadOnlyList<MemoryReference>? memories = null)
    {
        Guid? id = null;
        bool terminal = false;
        string? capability = null, promptVersion = null;
        try
        {
            // User cancellation does not abort POST: first obtain identity, then cancel that task.
            input.Validate();
            var selection = memories?.ToArray();
            bool memoryAsk = selection is { Length: > 0 };
            if (memoryAsk && input.Action != AssistantAction.Ask) throw new DesktopException(DesktopError.InvalidRequest);
            capability = input.Action switch { AssistantAction.Translate => "translate", AssistantAction.Summarize => "summarize", AssistantAction.Ask => "ask", _ => throw new DesktopException(DesktopError.InvalidRequest) };
            promptVersion = memoryAsk ? "memory-ask-v1" : capability + "-v1";
            var task = await (input.Action switch
            {
                AssistantAction.Translate => client.SubmitTranslateAsync(new(input.Text, input.TargetLanguage), lifetime),
                AssistantAction.Summarize => client.SubmitSummarizeAsync(new(input.Text), lifetime),
                AssistantAction.Ask => memoryAsk ? client.SubmitMemoryAskAsync(new(input.Text, selection!), lifetime)
                    : client.SubmitAskAsync(new(input.Text), lifetime),
                _ => throw new DesktopException(DesktopError.InvalidRequest)
            });
            id = task.TaskId;
            Accepted = true;
            var started = System.Diagnostics.Stopwatch.StartNew();
            while (true)
            {
                progress(task);
                if (task.Terminal) { terminal = true; return task; }
                if (Volatile.Read(ref cancelRequested) != 0)
                {
                    task = await client.CancelAsync(id.Value, capability, lifetime, promptVersion);
                    if (!task.Terminal) throw new DesktopException(DesktopError.InvalidResponse);
                    continue;
                }
                if (started.Elapsed > TimeSpan.FromSeconds(190)) throw new DesktopException(DesktopError.ClientTimeout);
                await Task.WhenAny(Task.Delay(pollingInterval ?? TimeSpan.FromMilliseconds(300), lifetime), cancelSignal.Task);
                lifetime.ThrowIfCancellationRequested();
                if (Volatile.Read(ref cancelRequested) == 0) task = await client.GetAsync(id.Value, capability, lifetime, promptVersion);
            }
        }
        finally
        {
            if (id.HasValue && !terminal)
            {
                // Best effort on shutdown/communication failure; never claim confirmed cancellation.
                using var cleanup = new CancellationTokenSource(TimeSpan.FromSeconds(2));
                try { await client.CancelAsync(id.Value, capability!, cleanup.Token, promptVersion); }
                catch (Exception error) when (error is DesktopException or OperationCanceledException) { }
            }
        }
    }
}
