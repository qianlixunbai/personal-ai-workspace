using System.Net;
using System.Net.Http;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Threading;
using PersonalAiWorkspace.Core;
using PersonalAiWorkspace.Desktop.Bridge;
using Xunit;
using static PersonalAiWorkspace.Desktop.Tests.MemoryClientTests;

namespace PersonalAiWorkspace.Desktop.Tests;

public sealed class MemoryWindowTests
{
    private sealed class FallbackActions : IWorkspaceNativeActions
    {
        internal readonly List<NativeWorkspaceEntry> Opened = [];
        internal readonly TaskCompletionSource Pending = new(TaskCreationOptions.RunContinuationsAsynchronously);
        internal bool Reject;
        internal int StatusReads;
        public Task<ShellStatus> StatusAsync(CancellationToken cancellation)
        {
            StatusReads++;
            throw new InvalidOperationException("Unexpected bootstrap.");
        }
        public Task OpenAsync(NativeWorkspaceEntry entry, CancellationToken cancellation)
        {
            Opened.Add(entry);
            return Reject ? Task.FromException(new InvalidOperationException("private-native-detail")) : Pending.Task;
        }
    }

    [Fact]
    public Task WebViewFailureOffersExistingMemoryRouteWithoutStartingBrowserOrLosingFailure() => StaAsync(async () =>
    {
        var native = new FallbackActions();
        var window = new MainWorkspaceWindow(native);
        try
        {
            var button = (Button)window.FindName("NativeMemoryFallbackButton");
            var failure = (TextBlock)window.FindName("FallbackMessage");
            var status = (TextBlock)window.FindName("NativeMemoryStatus");
            Assert.Equal(Visibility.Collapsed, button.Visibility);
            button.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
            Assert.Empty(native.Opened);

            window.Host.Fail("Synthetic WebView failure");
            Assert.True(window.FallbackVisible);
            Assert.Equal(Visibility.Visible, button.Visibility);
            button.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
            Assert.False(button.IsEnabled);
            button.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
            Assert.Equal(NativeWorkspaceEntry.Memory, Assert.Single(native.Opened));
            native.Pending.SetResult();
            await DrainAsync();
            Assert.True(button.IsEnabled);
            Assert.Equal("Synthetic WebView failure", failure.Text);
            Assert.Equal(Visibility.Collapsed, status.Visibility);

            native.Reject = true;
            button.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
            await DrainAsync();
            Assert.Equal(new[] { NativeWorkspaceEntry.Memory, NativeWorkspaceEntry.Memory }, native.Opened);
            Assert.True(button.IsEnabled);
            Assert.Equal(Visibility.Visible, status.Visibility);
            Assert.False(string.IsNullOrWhiteSpace(status.Text));
            Assert.DoesNotContain("private-native-detail", status.Text);
            Assert.Equal("Synthetic WebView failure", failure.Text);
            Assert.False(window.IsLoaded);
            Assert.Null(window.Host.UserDataFolder);
            Assert.Equal("NOT_STARTED", window.Host.StartupStage);
            Assert.Equal(0, native.StatusReads);
        }
        finally { window.DisposeImmediately(); }
    });

    private sealed class Store
    {
        internal readonly Dictionary<Guid, MemoryItem> Items = [];
        internal int Reads, Mutations;
        internal int Recalls;
        internal string? RecallQuery;
        internal bool LoseNextWriteResponse;
        internal bool CorruptNextWriteReceipt;
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
        private void AfterWrite()
        {
            if (!LoseNextWriteResponse) return;
            LoseNextWriteResponse = false;
            throw new HttpRequestException(PrivateContent);
        }
        private string Receipt(MemoryItem item)
        {
            if (!CorruptNextWriteReceipt) return Json(item);
            CorruptNextWriteReceipt = false;
            return Json(item with { Content = "synthetic-unrelated-body" });
        }
        internal RuntimeClient Client() => new(new Handler(async (request, ct) =>
        {
            if (request.RequestUri!.AbsolutePath == "/api/v1/memory/recall")
            {
                Recalls++;
                using var input = JsonDocument.Parse(await request.Content!.ReadAsStringAsync(ct));
                RecallQuery = input.RootElement.GetProperty("query").GetString();
                string? type = input.RootElement.GetProperty("type").GetString();
                var matches = Items.Values.Where(x => x.Status == MemoryStatus.ACTIVE && (type is null || x.Type.ToString() == type))
                    .OrderByDescending(x => x.UpdatedAt).ThenBy(x => x.Id.ToString("D"), StringComparer.Ordinal).ToArray();
                return Response("{\"hits\":[" + string.Join(",", matches.Take(20).Select(x =>
                    "{\"item\":" + Json(x) + ",\"score\":1,\"matchedTerms\":[\"java\"]}")) + "],\"total\":" + matches.Length + ",\"limit\":20}");
            }
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
            if (request.Method == HttpMethod.Get) return Items.TryGetValue(Guid.Parse(path.Split('/')[5]), out var found)
                ? Response(Json(found)) : Response(Error("MEMORY_NOT_FOUND"), HttpStatusCode.NotFound);
            using var body = JsonDocument.Parse(await request.Content!.ReadAsStringAsync(ct)); var root = body.RootElement;
            if (path == "/api/v1/memory/items")
            {
                var added = Add() with { Type = Enum.Parse<MemoryType>(root.GetProperty("type").GetString()!),
                    Title = root.GetProperty("title").GetString()!, Content = root.GetProperty("content").GetString()! };
                Items[added.Id] = added;
                AfterWrite();
                var response = Response(Receipt(added), HttpStatusCode.Created);
                response.Headers.Location = new Uri($"/api/v1/memory/items/{added.Id:D}", UriKind.Relative); return response;
            }
            var id = Guid.Parse(path.Split('/')[5]);
            if (!Items.TryGetValue(id, out var old)) return Response(Error("MEMORY_NOT_FOUND"), HttpStatusCode.NotFound);
            if (root.GetProperty("expectedRevision").GetInt64() != old.Revision)
                return Response(Error("MEMORY_REVISION_CONFLICT"), HttpStatusCode.Conflict);
            if (request.Method == HttpMethod.Delete) { Items.Remove(id); AfterWrite(); return new(HttpStatusCode.NoContent); }
            var updated = old with { Revision = old.Revision + 1, UpdatedAt = DateTimeOffset.UtcNow };
            if (request.Method == HttpMethod.Put) updated = updated with
            { Type = Enum.Parse<MemoryType>(root.GetProperty("type").GetString()!), Title = root.GetProperty("title").GetString()!, Content = root.GetProperty("content").GetString()! };
            else updated = updated with { Status = path.EndsWith("/archive", StringComparison.Ordinal) ? MemoryStatus.ARCHIVED : MemoryStatus.ACTIVE };
            Items[id] = updated; AfterWrite(); return Response(Receipt(updated));
        }), () => Token);
    }


    [Fact]
    public Task UnknownWriteBlocksReplayUntilExplicitReadAndKnownFailuresRemainRetryable() => StaAsync(async () =>
    {
        var store = new Store(); using var runtime = store.Client(); bool approve = false;
        var window = new MemoryWindow(runtime, _ => approve);
        try
        {
            window.ContentBox.Text = PrivateContent;
            await window.SaveAsync(); // Validation fails before dispatch: retry remains possible.
            Assert.Equal(0, store.Mutations);
            Assert.Equal(ErrorText.For(DesktopError.MemoryInvalid), window.StatusText.Text);
            Assert.False(window.SaveButton.IsEnabled);

            window.TitleBox.Text = PrivateTitle;
            store.LoseNextWriteResponse = true;
            await window.SaveAsync();
            var created = Assert.Single(store.Items.Values);
            Assert.Equal(1, store.Mutations);
            Assert.True(window.Dirty);
            Assert.False(window.SaveButton.IsEnabled);
            Assert.Contains("操作结果待确认", window.MetadataText.Text);
            Safe(window.StatusText.Text);
            await window.ReviewItemAsync(created);
            Assert.False(window.AdoptReviewButton.IsEnabled);
            await window.AdoptReviewAsync(); Assert.Contains("New", window.MetadataText.Text);
            await window.SaveAsync();
            Assert.Equal(1, store.Mutations);

            await window.RefreshAsync(); // Listing cannot acknowledge an uncertain write.
            Assert.False(window.SaveButton.IsEnabled);
            await window.SelectItemAsync(created); // Existing discard confirmation still applies.
            Assert.True(window.Dirty);
            Assert.False(window.SaveButton.IsEnabled);
            approve = true;
            await window.SelectItemAsync(created);
            Assert.False(window.Dirty);
            Assert.DoesNotContain("操作结果待确认", window.MetadataText.Text);

            window.ContentBox.Text += " local update";
            store.Pending = new();
            var refresh = window.RefreshAsync();
            store.Pending.SetException(new HttpRequestException(PrivateContent));
            await refresh;
            store.Pending = null;
            Assert.True(window.SaveButton.IsEnabled); // A failed read cannot block writes as uncertain.

            store.LoseNextWriteResponse = true;
            await window.SaveAsync();
            Assert.Equal(2, store.Mutations);
            Assert.Equal(2, store.Items[created.Id].Revision);
            Assert.True(window.Dirty);
            Assert.False(window.SaveButton.IsEnabled);
            Assert.False(window.LifecycleButton.IsEnabled);
            Assert.False(window.DeleteButton.IsEnabled);
            await window.ReviewItemAsync(created);
            Assert.False(window.AdoptReviewButton.IsEnabled);
            await window.AdoptReviewAsync(); Assert.False(window.SaveButton.IsEnabled);
            await window.SaveAsync(); await window.ChangeStatusAsync(); await window.DeleteAsync();
            Assert.Equal(2, store.Mutations);
            approve = false; await window.ReloadAsync();
            Assert.True(window.Dirty);
            Assert.False(window.SaveButton.IsEnabled);
            approve = true; await window.ReloadAsync();
            Assert.False(window.Dirty);
            Assert.DoesNotContain("操作结果待确认", window.MetadataText.Text);
            Assert.Equal(store.Items[created.Id].Content, window.ContentBox.Text);

            window.ContentBox.Text += " next draft";
            string submitted = window.ContentBox.Text;
            store.CorruptNextWriteReceipt = true;
            await window.SaveAsync(); // Store applied the write; its reply describes unrelated content.
            Assert.True(window.Dirty); Assert.Equal(submitted, window.ContentBox.Text);
            Assert.Contains("操作结果待确认", window.StatusText.Text);
            Assert.False(window.SaveButton.IsEnabled);
            await window.SaveAsync(); Assert.Equal(3, store.Mutations);
            await window.ReloadAsync();
            Assert.False(window.Dirty); Assert.Equal(submitted, window.ContentBox.Text);

            window.ContentBox.Text += " uncertain edit";
            store.LoseNextWriteResponse = true; await window.SaveAsync();
            store.Items.Remove(created.Id); // A later missing read cannot resolve the earlier write.
            await window.ReviewItemAsync(created);
            window.RecoverDraftAsNew(); await window.SaveAsync();
            Assert.Equal(4, store.Mutations); Assert.Empty(store.Items);
            Assert.Contains("操作结果待确认", window.MetadataText.Text);
            Assert.Equal(Visibility.Collapsed, window.RecoverDraftButton.Visibility);
            Assert.Contains(created.Id.ToString("D"), window.MetadataText.Text);
            Assert.True(window.Dirty);
        }
        finally { approve = true; window.TryClose(); }
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
    public Task RelatedManagementSearchKeepsDraftThroughReviewSaveArchiveAndRestore() => StaAsync(async () =>
    {
        var store = new Store(); var original = store.Add(); using var runtime = store.Client();
        var window = new MemoryWindow(runtime, _ => true);
        const string draft = "Synthetic java draft retained exactly 😀";
        try
        {
            window.TitleBox.Text = "Synthetic revised"; window.ContentBox.Text = draft;
            window.SearchBox.Text = "java 测试";
            await window.RefreshAsync(search: true); Assert.Empty(window.MemoryList.Items);
            await window.RecallAsync(); Assert.Equal("java 测试", store.RecallQuery);
            Assert.Equal(original, Assert.Single(window.MemoryList.Items.Cast<MemoryItem>()));
            Assert.Equal(draft, window.ContentBox.Text); Assert.Equal(0, store.Mutations);
            Assert.False(window.NextButton.IsEnabled); Assert.False(window.PreviousButton.IsEnabled);
            Assert.Contains("词语匹配", window.StatusText.Text);
            await window.ReviewItemAsync(original); Assert.Contains("java", window.ReviewMetadata.Text);
            await window.AdoptReviewAsync(); Assert.Equal(0, store.Mutations);
            await window.SaveAsync(); Assert.Equal(draft, store.Items[original.Id].Content);
            Assert.Equal(1, store.Mutations); Assert.Equal(2, store.Recalls);
            await window.ChangeStatusAsync(); Assert.Empty(window.MemoryList.Items);
            Assert.Equal(MemoryStatus.ARCHIVED, store.Items[original.Id].Status);
            window.SearchBox.Text = "";
            window.StatusFilter.SelectedIndex = 1; await DrainAsync();
            Assert.False(window.RecallButton.IsEnabled);
            await window.RefreshAsync(search: true);
            Assert.Single(window.MemoryList.Items.Cast<MemoryItem>());
            await window.ChangeStatusAsync(); Assert.Empty(window.MemoryList.Items);
            Assert.Equal(MemoryStatus.ACTIVE, store.Items[original.Id].Status);
            window.StatusFilter.SelectedIndex = 0; await DrainAsync();
            window.SearchBox.Text = "java";
            for (int i = 0; i < 20; i++) store.Add();
            await window.RecallAsync(); Assert.Equal(20, window.MemoryList.Items.Count);
            Assert.Contains("前 20", window.StatusText.Text); Assert.False(window.NextButton.IsEnabled);
            window.TypeFilter.SelectedIndex = 1; await DrainAsync();
            Assert.Empty(window.MemoryList.Items); Assert.Contains("不代表", window.StatusText.Text);
            Assert.Equal(3, store.Mutations); Assert.Equal(draft, window.ContentBox.Text);
            await window.RefreshAsync(search: true); Assert.Contains("原文查找", window.StatusText.Text);
        }
        finally { window.TryClose(); }
    });

    [Fact]
    public Task NativeReviewKeepsDraftAndOnlyExplicitSaveRevisesTheReviewedRecord() => StaAsync(async () =>
    {
        var store = new Store(); var original = store.Add(); using var runtime = store.Client(); bool approve = false;
        var window = new MemoryWindow(runtime, _ => approve);
        const string title = "Synthetic revised title", draft = "  Synthetic revised body 😀\nexact spacing  ";
        try
        {
            window.TitleBox.Text = title; window.ContentBox.Text = draft; window.EditorType.SelectedIndex = 0;
            await window.RefreshAsync();
            window.ReviewTarget.SelectedItem = original;
            window.ReviewButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent)); await DrainAsync();
            Assert.Equal(original.Content, window.ReviewContent.Text);
            Assert.True(window.ReviewContent.IsReadOnly); Assert.True(window.ReviewTitle.IsReadOnly);
            Assert.Equal(draft, window.ContentBox.Text); Assert.True(window.AdoptReviewButton.IsEnabled);
            await window.AdoptReviewAsync(); // No confirmation: still a new draft.
            Assert.Contains("New", window.MetadataText.Text); Assert.Equal(0, store.Mutations);

            approve = true;
            store.Items[original.Id] = original with { Revision = 2, Content = "Synthetic changed while reviewing" };
            await window.AdoptReviewAsync();
            Assert.Contains("New", window.MetadataText.Text); Assert.Contains("已变更", window.StatusText.Text);
            Assert.Equal(store.Items[original.Id].Content, window.ReviewContent.Text);
            await window.AdoptReviewAsync();
            Assert.Contains(original.Id.ToString("D"), window.MetadataText.Text);
            Assert.Equal(title, window.TitleBox.Text); Assert.Equal(draft, window.ContentBox.Text);
            Assert.Equal(0, window.EditorType.SelectedIndex); Assert.True(window.Dirty);
            Assert.Equal(0, store.Mutations);
            await window.SaveAsync();
            var saved = Assert.Single(store.Items.Values);
            Assert.Equal(original.Id, saved.Id); Assert.Equal(3, saved.Revision);
            Assert.Equal(draft, saved.Content); Assert.Equal(title, saved.Title); Assert.Equal(MemoryType.PREFERENCE, saved.Type);
            Assert.Equal(1, store.Mutations); Assert.False(window.Dirty);

            window.NewItem(); window.TitleBox.Text = title; window.ContentBox.Text = draft;
            var archived = store.Add(status: MemoryStatus.ARCHIVED);
            await window.ReviewItemAsync(archived); Assert.False(window.AdoptReviewButton.IsEnabled);
            await window.AdoptReviewAsync(); Assert.Contains("New", window.MetadataText.Text);
            store.Failure = "MEMORY_NOT_FOUND"; await window.ReviewItemAsync(original);
            Assert.Empty(window.ReviewContent.Text); Assert.False(window.AdoptReviewButton.IsEnabled);
            Assert.Equal(draft, window.ContentBox.Text); Assert.Equal(1, store.Mutations);
        }
        finally { approve = true; window.TryClose(); }
        Assert.Empty(window.ReviewContent.Text); Assert.Empty(window.ReviewTitle.Text);
    });

    [Fact]
    public Task NativeDuplicateCheckFindsExactBodiesAndContinuesIntoExplicitSameRecordSave() => StaAsync(async () =>
    {
        var store = new Store(); using var runtime = store.Client(); var window = new MemoryWindow(runtime, _ => true);
        string body = string.Concat(Enumerable.Repeat("😀", 160)) + " synthetic exact suffix";
        var active = store.Add(); store.Items[active.Id] = active = active with { Content = body };
        var archived = store.Add(status: MemoryStatus.ARCHIVED); store.Items[archived.Id] = archived = archived with { Content = body };
        var similar = store.Add(); store.Items[similar.Id] = similar with { Content = body + " different" };
        try
        {
            window.TitleBox.Text = "Synthetic reviewed memory"; window.ContentBox.Text = body;
            int reads = store.Reads;
            await window.CheckDuplicatesAsync();
            Assert.Equal(reads + 2, store.Reads); Assert.Equal(0, store.Mutations);
            Assert.Equal(new[] { active.Id, archived.Id }, window.DuplicateMatches.Items.Cast<MemoryItem>().Select(x => x.Id));
            Assert.DoesNotContain("检查不完整", window.DuplicateSummary.Text);
            window.DuplicateMatches.SelectedItem = archived;
            window.ReviewDuplicateButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent)); await DrainAsync();
            Assert.Equal(body, window.ReviewContent.Text); Assert.False(window.AdoptReviewButton.IsEnabled);
            window.DuplicateMatches.SelectedItem = active;
            window.ReviewDuplicateButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent)); await DrainAsync();
            Assert.True(window.AdoptReviewButton.IsEnabled); Assert.Equal(body, window.ContentBox.Text);
            await window.AdoptReviewAsync(); Assert.Equal(0, store.Mutations);
            window.ContentBox.Text = "Synthetic merged body";
            Assert.Empty(window.DuplicateMatches.Items); Assert.Contains("重新检查", window.DuplicateSummary.Text);
            await window.SaveAsync();
            Assert.Equal(3, store.Items.Count); Assert.Equal("Synthetic merged body", store.Items[active.Id].Content);
            Assert.Equal(2, store.Items[active.Id].Revision); Assert.Equal(1, store.Mutations);

            await window.CheckDuplicatesAsync(); Assert.Empty(window.DuplicateMatches.Items); // Excludes current record.
            window.NewItem(); window.TitleBox.Text = "Synthetic new draft"; window.ContentBox.Text = body;
            for (int i = 0; i < 22; i++) { var item = store.Add(); store.Items[item.Id] = item with { Content = body }; }
            reads = store.Reads; await window.CheckDuplicatesAsync();
            Assert.Equal(reads + 2, store.Reads); Assert.Contains("检查不完整", window.DuplicateSummary.Text);
            Assert.Equal(20, window.DuplicateMatches.Items.Count); // 19 exact active + one archived; similar row is excluded.
            store.Failure = "MEMORY_REVISION_CONFLICT"; await window.CheckDuplicatesAsync();
            Assert.Empty(window.DuplicateMatches.Items); Assert.Contains("未能完成检查", window.DuplicateSummary.Text);
            Assert.Equal(body, window.ContentBox.Text); Assert.True(window.SaveButton.IsEnabled);
            store.Pending = new(); var pending = window.CheckDuplicatesAsync();
            Assert.True(window.TryClose()); Assert.True(store.PendingToken.IsCancellationRequested);
            store.Pending.SetResult(Response(Page())); await pending;
            Assert.Empty(window.DuplicateMatches.Items); Assert.Empty(window.DuplicateSummary.Text);
        }
        finally { window.TryClose(); }
    });

    [Fact]
    public Task ConflictCanBeReviewedAndMergedWithoutDiscardingDraftOrOverwritingUnseenChanges() => StaAsync(async () =>
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
        window.ReviewCurrentButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent)); await DrainAsync();
        Assert.Equal(store.Items[original.Id].Content, window.ReviewContent.Text);
        Assert.Equal(edits, window.ContentBox.Text); Assert.True(window.Stale); Assert.False(window.SaveButton.IsEnabled);
        await window.AdoptReviewAsync(); Assert.True(window.Stale); // Declined confirmation.
        var unrelated = store.Add(); await window.ReviewItemAsync(unrelated);
        Assert.False(window.AdoptReviewButton.IsEnabled); approve = true;
        await window.AdoptReviewAsync(); Assert.Contains(original.Id.ToString("D"), window.MetadataText.Text);
        await window.ReviewItemAsync(original);
        window.ContentBox.Text = window.ReviewContent.Text + " merged local-edit";
        string merged = window.ContentBox.Text;
        await window.AdoptReviewAsync(); Assert.False(window.Stale); Assert.True(window.Dirty);
        Assert.Equal(merged, window.ContentBox.Text); Assert.Equal(1, store.Mutations);
        store.Items[original.Id] = store.Items[original.Id] with { Revision = 6, Title = "Synthetic concurrent title" };
        await window.SaveAsync(); Assert.True(window.Stale); Assert.Equal(merged, window.ContentBox.Text);
        await window.ReviewItemAsync(original);
        window.TitleBox.Text = window.ReviewTitle.Text;
        await window.AdoptReviewAsync(); await window.SaveAsync();
        Assert.Equal(7, store.Items[original.Id].Revision); Assert.Equal(merged, store.Items[original.Id].Content);
        Assert.Equal("Synthetic concurrent title", store.Items[original.Id].Title);
        Assert.False(window.Dirty); Assert.Equal(3, store.Mutations);
        Safe(window.StatusText.Text); Assert.True(window.TryClose());
    });

    [Fact]
    public Task MissingRecordDraftCanBeExplicitlySavedAsNewWithoutRevivingDeletedRecord() => StaAsync(async () =>
    {
        var store = new Store(); var original = store.Add(); using var runtime = store.Client();
        bool approve = true;
        var window = new MemoryWindow(runtime, _ => approve);
        await window.RefreshAsync(); await window.SelectItemAsync(original);
        window.EditorType.SelectedIndex = 0;
        window.TitleBox.Text = "  Synthetic 草稿 🧭  ";
        window.ContentBox.Text += "\r\n local 草稿 🧭  "; string edits = window.ContentBox.Text;
        string title = window.TitleBox.Text;
        window.RecoverDraftAsNew(); Assert.Contains(original.Id.ToString("D"), window.MetadataText.Text);
        await window.ChangeStatusAsync(); Assert.True(window.Dirty); Assert.True(edits == window.ContentBox.Text, "Archive lost edits.");
        store.Items.Remove(original.Id); await window.SaveAsync();
        Assert.False(window.SaveButton.IsEnabled); Assert.False(window.LifecycleButton.IsEnabled); Assert.False(window.DeleteButton.IsEnabled);
        Assert.True(edits == window.ContentBox.Text, "Not found lost edits.");
        Assert.Contains("原记录当前不存在", window.StatusText.Text); Safe(window.StatusText.Text);
        int mutations = store.Mutations; await window.SaveAsync(); await window.ChangeStatusAsync(); await window.DeleteAsync();
        Assert.Equal(mutations, store.Mutations);
        Assert.True(window.RecoverDraftButton.IsEnabled);
        approve = false; window.RecoverDraftButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
        Assert.Contains(original.Id.ToString("D"), window.MetadataText.Text); Assert.Equal(edits, window.ContentBox.Text);
        approve = true; window.RecoverDraftButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
        Assert.Equal(title, window.TitleBox.Text); Assert.Equal(edits, window.ContentBox.Text);
        Assert.Equal(0, window.EditorType.SelectedIndex); Assert.True(window.Dirty);
        Assert.Equal(mutations, store.Mutations); Assert.Empty(store.Items);
        Assert.Contains("尚未保存", window.StatusText.Text); Assert.True(window.SaveButton.IsEnabled);
        Assert.Empty(window.ReviewContent.Text);
        await window.SaveAsync();
        var recovered = Assert.Single(store.Items.Values);
        Assert.NotEqual(original.Id, recovered.Id); Assert.False(store.Items.ContainsKey(original.Id));
        Assert.Equal(1, recovered.Revision); Assert.Equal(MemoryStatus.ACTIVE, recovered.Status);
        Assert.Equal(MemoryType.PREFERENCE, recovered.Type); Assert.Equal(title, recovered.Title); Assert.Equal(edits, recovered.Content);
        Assert.Equal(mutations + 1, store.Mutations); Assert.False(window.Dirty); Assert.True(window.TryClose());
    });


    [Fact]
    public Task BusyWriteRetainsWindowUntilReceiptAndReadCloseStillCancels() => StaAsync(async () =>
    {
        var store = new Store(); using var runtime = store.Client(); var window = new MemoryWindow(runtime, _ => true);
        window.TitleBox.Text = PrivateTitle; window.ContentBox.Text = PrivateContent; window.SearchBox.Text = PrivateTitle;
        store.Pending = new(); var pending = window.SaveAsync();
        Assert.All(new[] { window.SearchButton, window.RefreshButton, window.NewButton, window.SaveButton,
            window.LifecycleButton, window.DeleteButton, window.ReloadButton, window.PreviousButton, window.NextButton }, x => Assert.False(x.IsEnabled));
        Assert.False(window.StatusFilter.IsEnabled); Assert.False(window.TypeFilter.IsEnabled); Assert.False(window.MemoryList.IsEnabled);
        Assert.True(window.ContentBox.IsReadOnly); await window.SaveAsync(); await window.RefreshAsync(); window.NewItem();
        Assert.Equal(1, store.Mutations); Assert.Equal(0, store.Reads);
        try
        {
            Assert.False(window.TryClose()); Assert.False(store.PendingToken.IsCancellationRequested);
            Assert.Contains("等待结果", window.StatusText.Text);
            Assert.True(window.Dirty); Assert.Equal(PrivateContent, window.ContentBox.Text);
            var receipt = store.Pending; store.Pending = null;
            var response = Response(Item(), HttpStatusCode.Created); response.Headers.Location = new Uri($"/api/v1/memory/items/{Id:D}", UriKind.Relative);
            receipt.SetResult(response); await pending;
            Assert.False(window.Dirty); Assert.Contains("已保存", window.StatusText.Text);

            // Lifecycle writes have no dirty draft; they must still retain the pending result.
            store.Pending = new(); pending = window.ChangeStatusAsync();
            Assert.False(window.TryClose()); Assert.False(store.PendingToken.IsCancellationRequested);
            receipt = store.Pending; store.Pending = null;
            receipt.SetException(new HttpRequestException(PrivateContent)); await pending;
            Assert.Contains("操作结果待确认", window.StatusText.Text);
            Assert.False(window.LifecycleButton.IsEnabled);
            Assert.True(window.TryClose()); AssertCleared(window);
            await window.SaveAsync(); await window.RefreshAsync(); Assert.Equal(2, store.Mutations);
        }
        finally
        {
            store.Pending?.TrySetException(new HttpRequestException("Synthetic fixture cleanup"));
            await pending;
            window.TryClose();
        }
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
