using System.Net;
using System.Net.Http;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Threading;
using PersonalAiWorkspace.Core;
using Xunit;
using static PersonalAiWorkspace.Desktop.Tests.MemoryClientTests;

namespace PersonalAiWorkspace.Desktop.Tests;

public sealed class MemoryWindowTests
{
    private sealed class Store
    {
        internal readonly Dictionary<Guid, MemoryItem> Items = [];
        internal int Reads, Mutations;
        internal string? Failure;
        internal TaskCompletionSource<HttpResponseMessage>? Pending;
        internal CancellationToken PendingToken;
        internal MemoryItem Add(long revision = 1, MemoryStatus status = MemoryStatus.ACTIVE)
        {
            var item = new MemoryItem(Guid.NewGuid(), MemoryType.PROJECT_NOTE, PrivateTitle, PrivateContent, status, revision,
                MemorySource.MANUAL, DateTimeOffset.UtcNow.AddMinutes(-1), DateTimeOffset.UtcNow);
            Items.Add(item.Id, item); return item;
        }
        private static string Json(MemoryItem item) => JsonSerializer.Serialize(new
        {
            id = item.Id, type = item.Type.ToString(), title = item.Title, content = item.Content, status = item.Status.ToString(),
            revision = item.Revision, source = "MANUAL", createdAt = item.CreatedAt, updatedAt = item.UpdatedAt
        });
        internal RuntimeClient Client() => new(new Handler(async (request, ct) =>
        {
            if (request.Method == HttpMethod.Get) Reads++; else Mutations++;
            if (Pending is not null) { PendingToken = ct; return await Pending.Task; }
            if (Failure is not null)
            {
                string failure = Failure; Failure = null;
                return Response(Error(failure), failure == "MEMORY_NOT_FOUND" ? HttpStatusCode.NotFound : HttpStatusCode.Conflict);
            }
            string path = request.RequestUri!.AbsolutePath;
            if (request.Method == HttpMethod.Get && path == "/api/v1/memory/items")
            {
                var query = request.RequestUri.Query.TrimStart('?').Split('&').Select(x => x.Split('=', 2))
                    .ToDictionary(x => x[0], x => Uri.UnescapeDataString(x[1]));
                var status = Enum.Parse<MemoryStatus>(query["status"]);
                var filtered = Items.Values.Where(x => x.Status == status
                    && (!query.ContainsKey("type") || x.Type.ToString() == query["type"])
                    && (x.Title.Contains(query["query"], StringComparison.Ordinal) || x.Content.Contains(query["query"], StringComparison.Ordinal))).ToArray();
                int page = int.Parse(query["page"]), limit = int.Parse(query["limit"]);
                return Response(Page(filtered.Skip(page * limit).Take(limit).Select(Json).ToArray(), filtered.Length, page, limit));
            }
            if (request.Method == HttpMethod.Get) return Response(Json(Items[Guid.Parse(path.Split('/')[5])]));
            using var body = JsonDocument.Parse(await request.Content!.ReadAsStringAsync(ct)); var root = body.RootElement;
            if (path == "/api/v1/memory/items")
            {
                var added = Add() with { Type = Enum.Parse<MemoryType>(root.GetProperty("type").GetString()!),
                    Title = root.GetProperty("title").GetString()!, Content = root.GetProperty("content").GetString()! };
                Items[added.Id] = added;
                var response = Response(Json(added), HttpStatusCode.Created);
                response.Headers.Location = new Uri($"/api/v1/memory/items/{added.Id:D}", UriKind.Relative); return response;
            }
            var id = Guid.Parse(path.Split('/')[5]);
            if (!Items.TryGetValue(id, out var old)) return Response(Error("MEMORY_NOT_FOUND"), HttpStatusCode.NotFound);
            if (root.GetProperty("expectedRevision").GetInt64() != old.Revision)
                return Response(Error("MEMORY_REVISION_CONFLICT"), HttpStatusCode.Conflict);
            if (request.Method == HttpMethod.Delete) { Items.Remove(id); return new(HttpStatusCode.NoContent); }
            var updated = old with { Revision = old.Revision + 1, UpdatedAt = DateTimeOffset.UtcNow };
            if (request.Method == HttpMethod.Put) updated = updated with
            { Type = Enum.Parse<MemoryType>(root.GetProperty("type").GetString()!), Title = root.GetProperty("title").GetString()!, Content = root.GetProperty("content").GetString()! };
            else updated = updated with { Status = path.EndsWith("/archive", StringComparison.Ordinal) ? MemoryStatus.ARCHIVED : MemoryStatus.ACTIVE };
            Items[id] = updated; return Response(Json(updated));
        }), () => Token);
    }

    [Fact]
    public Task WpfExplicitCreateEditLifecycleDeleteAndSearchNeverAutosave() => StaAsync(async () =>
    {
        var store = new Store(); using var runtime = store.Client();
        bool approve = false; var decisions = new List<MemoryConfirmation>();
        var window = new MemoryWindow(runtime, x => { decisions.Add(x); return approve; });
        window.Show(); await DrainAsync(); Assert.Equal(0, store.Mutations);
        window.NewButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
        window.TitleBox.Text = PrivateTitle; window.ContentBox.Text = PrivateContent;
        Assert.Equal(0, store.Mutations); Assert.True(window.Dirty);
        window.SaveButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent)); await DrainAsync();
        Assert.Equal(1, store.Mutations); Assert.False(window.Dirty); Assert.Single(window.MemoryList.Items);
        var item = Assert.Single(store.Items.Values);
        window.ContentBox.Text += " changed"; Assert.Equal(1, store.Mutations);
        await window.SaveAsync(); Assert.Equal(2, store.Items[item.Id].Revision); Assert.False(window.Dirty);
        window.SearchBox.Text = PrivateTitle; int reads = store.Reads;
        Assert.Equal(reads, store.Reads); window.SearchButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent)); await DrainAsync();
        Assert.Single(window.MemoryList.Items);
        await window.ChangeStatusAsync(); Assert.Empty(window.MemoryList.Items); Assert.Equal("Restore", window.LifecycleButton.Content);
        window.StatusFilter.SelectedIndex = 1; await DrainAsync(); Assert.Single(window.MemoryList.Items);
        await window.ChangeStatusAsync(); Assert.Empty(window.MemoryList.Items); Assert.Equal("Archive", window.LifecycleButton.Content);
        int mutations = store.Mutations;
        await window.DeleteAsync(); Assert.Equal(mutations, store.Mutations); Assert.Contains(MemoryConfirmation.Delete, decisions);
        approve = true; await window.DeleteAsync(); Assert.Empty(store.Items); Assert.Empty(window.ContentBox.Text);
        Safe(window.StatusText.Text); Assert.True(window.TryClose()); AssertCleared(window);
    });

    [Fact]
    public Task DirtySwitchNewReloadAndCloseRespectConfirmationAndSuccessfulSaveClearsDirty() => StaAsync(async () =>
    {
        var store = new Store(); var first = store.Add(); var second = store.Add(); using var runtime = store.Client();
        bool approve = false; var decisions = new List<MemoryConfirmation>();
        var window = new MemoryWindow(runtime, x => { decisions.Add(x); return approve; });
        await window.RefreshAsync(); await window.SelectItemAsync(first); window.ContentBox.Text += " unsaved";
        string edits = window.ContentBox.Text; int reads = store.Reads;
        await window.SelectItemAsync(second); window.NewItem(); await window.ReloadAsync();
        Assert.False(window.TryClose()); Assert.Equal(reads, store.Reads); Assert.True(edits == window.ContentBox.Text, "Dirty editor changed.");
        Assert.Equal(new[] { MemoryConfirmation.Discard, MemoryConfirmation.Discard, MemoryConfirmation.Reload, MemoryConfirmation.Discard }, decisions);
        approve = true; await window.ReloadAsync(); Assert.False(window.Dirty);
        window.ContentBox.Text += " unsaved"; await window.SelectItemAsync(second); Assert.False(window.Dirty);
        window.TitleBox.Text += " unsaved"; window.NewItem(); Assert.Empty(window.TitleBox.Text); Assert.Empty(window.ContentBox.Text);
        window.TitleBox.Text = PrivateTitle; window.ContentBox.Text = PrivateContent;
        await window.SaveAsync(); Assert.False(window.Dirty); Assert.True(window.TryClose());
    });

    [Fact]
    public Task ConflictPreservesEditsBlocksAllMutationsAndOnlyConfirmedReloadUsesLatestRevision() => StaAsync(async () =>
    {
        var store = new Store(); var original = store.Add(); using var runtime = store.Client(); bool approve = false;
        var window = new MemoryWindow(runtime, _ => approve);
        await window.RefreshAsync(); await window.SelectItemAsync(original);
        window.ContentBox.Text += " local-edit"; string edits = window.ContentBox.Text;
        store.Items[original.Id] = original with { Revision = 5, Content = PrivateContent + " newer" };
        await window.SaveAsync(); Assert.True(window.Stale); Assert.True(window.Dirty);
        Assert.True(edits == window.ContentBox.Text, "Conflict lost local edits."); Assert.Equal(1, store.Mutations);
        Assert.False(window.SaveButton.IsEnabled); Assert.False(window.LifecycleButton.IsEnabled); Assert.False(window.DeleteButton.IsEnabled);
        await window.SaveAsync(); await window.ChangeStatusAsync(); await window.DeleteAsync();
        Assert.Equal(1, store.Mutations); await window.ReloadAsync(); Assert.True(window.Stale);
        approve = true; await window.ReloadAsync(); Assert.False(window.Stale); Assert.False(window.Dirty);
        Assert.True(store.Items[original.Id].Content == window.ContentBox.Text, "Reload differs.");
        window.ContentBox.Text += " explicit"; await window.SaveAsync(); Assert.Equal(6, store.Items[original.Id].Revision);
        Safe(window.StatusText.Text); Assert.True(window.TryClose());
    });

    [Fact]
    public Task NotFoundKeepsPrivateEditsDisablesMutationAndArchiveKeepsUnsavedEditor() => StaAsync(async () =>
    {
        var store = new Store(); var original = store.Add(); using var runtime = store.Client();
        var window = new MemoryWindow(runtime, _ => true);
        await window.RefreshAsync(); await window.SelectItemAsync(original);
        window.ContentBox.Text += " local"; string edits = window.ContentBox.Text;
        await window.ChangeStatusAsync(); Assert.True(window.Dirty); Assert.True(edits == window.ContentBox.Text, "Archive lost edits.");
        store.Items.Remove(original.Id); await window.SaveAsync();
        Assert.False(window.SaveButton.IsEnabled); Assert.False(window.LifecycleButton.IsEnabled); Assert.False(window.DeleteButton.IsEnabled);
        Assert.True(edits == window.ContentBox.Text, "Not found lost edits.");
        Assert.Equal(ErrorText.For(DesktopError.MemoryNotFound), window.StatusText.Text); Safe(window.StatusText.Text);
        int mutations = store.Mutations; await window.SaveAsync(); await window.ChangeStatusAsync(); await window.DeleteAsync();
        Assert.Equal(mutations, store.Mutations); window.NewItem(); Assert.Empty(window.ContentBox.Text); Assert.True(window.TryClose());
    });

    [Fact]
    public Task ExplicitFiltersPaginationAndDeletingLastRowSafelyReturnsToPreviousPage() => StaAsync(async () =>
    {
        var store = new Store(); for (int i = 0; i < 21; i++) store.Add(); using var runtime = store.Client();
        var window = new MemoryWindow(runtime, _ => true); await window.RefreshAsync();
        Assert.Equal(20, window.MemoryList.Items.Count); Assert.False(window.PreviousButton.IsEnabled); Assert.True(window.NextButton.IsEnabled);
        window.NextButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent)); await DrainAsync(); Assert.Single(window.MemoryList.Items);
        var last = (MemoryItem)window.MemoryList.Items[0]; await window.SelectItemAsync(last); await window.DeleteAsync();
        Assert.Equal(20, window.MemoryList.Items.Count); Assert.False(window.PreviousButton.IsEnabled); Assert.False(window.NextButton.IsEnabled);
        window.TypeFilter.SelectedIndex = 1; await DrainAsync(); Assert.Empty(window.MemoryList.Items);
        window.TypeFilter.SelectedIndex = 2; await DrainAsync(); Assert.Equal(20, window.MemoryList.Items.Count);
        window.SearchBox.Text = "synthetic-no-match-" + Guid.NewGuid(); Assert.Equal(20, window.MemoryList.Items.Count);
        await window.RefreshAsync(search: true); Assert.Empty(window.MemoryList.Items); Assert.True(window.TryClose());
    });

    [Fact]
    public Task BusySerializesOperationsCloseCancelsAndLateResponsesCannotRepopulateUi() => StaAsync(async () =>
    {
        var store = new Store(); using var runtime = store.Client(); var window = new MemoryWindow(runtime, _ => true);
        window.TitleBox.Text = PrivateTitle; window.ContentBox.Text = PrivateContent; window.SearchBox.Text = PrivateTitle;
        store.Pending = new(); var pending = window.SaveAsync();
        Assert.All(new[] { window.SearchButton, window.RefreshButton, window.NewButton, window.SaveButton,
            window.LifecycleButton, window.DeleteButton, window.ReloadButton, window.PreviousButton, window.NextButton }, x => Assert.False(x.IsEnabled));
        Assert.False(window.StatusFilter.IsEnabled); Assert.False(window.TypeFilter.IsEnabled); Assert.False(window.MemoryList.IsEnabled);
        Assert.True(window.ContentBox.IsReadOnly); await window.SaveAsync(); await window.RefreshAsync(); window.NewItem();
        Assert.Equal(1, store.Mutations); Assert.Equal(0, store.Reads);
        Assert.True(window.TryClose()); Assert.True(store.PendingToken.IsCancellationRequested); AssertCleared(window);
        var response = Response(Item(), HttpStatusCode.Created); response.Headers.Location = new Uri($"/api/v1/memory/items/{Id:D}", UriKind.Relative);
        store.Pending.SetResult(response); await pending; AssertCleared(window);
        await window.SaveAsync(); await window.RefreshAsync(); Assert.Equal(1, store.Mutations);
    });

    [Fact]
    public Task ClosingDuringListReadCancelsAndLateListCannotRepopulateClosedControls() => StaAsync(async () =>
    {
        var store = new Store(); using var runtime = store.Client(); var window = new MemoryWindow(runtime, _ => true);
        window.SearchBox.Text = PrivateTitle; store.Pending = new(); var pending = window.RefreshAsync(search: true);
        Assert.False(window.SearchButton.IsEnabled); Assert.True(window.TryClose());
        Assert.True(store.PendingToken.IsCancellationRequested);
        store.Pending.SetResult(Response(Page())); await pending; AssertCleared(window);
    });

    private sealed class Controller : IAssistantController
    {
        public bool Busy => false;
        public bool Exiting => false;
        public Task SubmitAsync() => Task.CompletedTask;
        public void CancelOperation() { }
        public Task CheckHealthAsync() => Task.CompletedTask;
        public Task ImportCredentialAsync(string file) => Task.CompletedTask;
        public void ForgetCredential() { }
    }

    [Fact]
    public Task AssistantCloseToTrayRespectsDirtyMemoryDecisionAndClosesOwnedMemorySafely() => StaAsync(async () =>
    {
        var store = new Store(); using var runtime = store.Client(); var assistant = new AssistantWindow(new Controller(), runtime);
        assistant.Show();
        bool approve = false; var memory = new MemoryWindow(runtime, _ => approve) { Owner = assistant };
        // Populate the actual single-instance slot without entering a blocking modal or real MessageBox.
        typeof(AssistantWindow).GetField("memoryWindow", System.Reflection.BindingFlags.Instance | System.Reflection.BindingFlags.NonPublic)!
            .SetValue(assistant, memory);
        memory.Show(); await DrainAsync(); memory.ContentBox.Text = PrivateContent;
        assistant.Close(); Assert.True(assistant.IsVisible); Assert.False(memory.IsClosed);
        approve = true; assistant.Close(); Assert.False(assistant.IsVisible); Assert.True(memory.IsClosed); AssertCleared(memory);
        assistant.ClearText();
    });

    private static void AssertCleared(MemoryWindow window)
    {
        Assert.Empty(window.TitleBox.Text); Assert.Empty(window.ContentBox.Text); Assert.Empty(window.SearchBox.Text);
        Assert.Empty(window.MemoryList.Items); Assert.Empty(window.StatusText.Text);
        Assert.False(window.ContentBox.IsUndoEnabled); Assert.False(window.TitleBox.IsUndoEnabled); Assert.False(window.SearchBox.IsUndoEnabled);
    }
    private static async Task DrainAsync() { for (int i = 0; i < 5; i++) await Dispatcher.Yield(DispatcherPriority.ApplicationIdle); }
    private static Task StaAsync(Func<Task> action)
    {
        var done = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var thread = new Thread(() =>
        {
            var dispatcher = Dispatcher.CurrentDispatcher;
            SynchronizationContext.SetSynchronizationContext(new DispatcherSynchronizationContext(dispatcher));
            dispatcher.BeginInvoke(new Action(async () =>
            {
                try { await action(); done.SetResult(); }
                catch (Exception error) { done.SetException(error); }
                finally { dispatcher.InvokeShutdown(); }
            }));
            Dispatcher.Run();
        });
        thread.SetApartmentState(ApartmentState.STA); thread.Start(); return done.Task;
    }
}
