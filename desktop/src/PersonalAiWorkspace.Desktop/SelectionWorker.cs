using System;
using System.Collections.Generic;
using System.IO;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Automation;
using PersonalAiWorkspace.Core;

namespace PersonalAiWorkspace.Desktop;

internal static class SelectionWorker
{
    internal static int Run(IntPtr foreground)
    {
        // No WPF windows, clipboard access, token access or disk writes in this helper.
        SelectionResult result = new(SelectionStatus.Failed);
        var thread = new Thread(() =>
        {
            try { result = Read(foreground); }
            catch (Exception) { result = new(SelectionStatus.Untrusted); }
        }) { IsBackground = true };
        thread.SetApartmentState(ApartmentState.MTA);
        thread.Start();
        thread.Join(); // Parent enforces a hard process deadline even if COM never returns.
        using var output = Console.OpenStandardOutput();
        JsonSerializer.Serialize(output, result);
        return 0;
    }

    private static SelectionResult Read(IntPtr foreground)
    {
        IntPtr focusWindow = Native.FocusWindow(foreground);
        if (!Native.Stable(foreground, focusWindow)) return new(SelectionStatus.ForegroundChanged);
        var focused = AutomationElement.FocusedElement;
        if (focused is null) return new(SelectionStatus.Untrusted);
        return ReadElement(focused, foreground, focusWindow,
            () => Native.Stable(foreground, focusWindow) && focused.Equals(AutomationElement.FocusedElement));
    }

    internal static SelectionResult ReadElement(AutomationElement focused, IntPtr foreground, IntPtr focusWindow, Func<bool> focusStable)
    {
        var context = new List<AutomationElement>();
        var current = focused;
        bool foundForeground = false;
        bool focusProtectionProven = false;
        // Only focused element and bounded ancestors, never a desktop-wide tree scan.
        for (int depth = 0; current is not null && depth < 24; depth++)
        {
            object password = current.GetCurrentPropertyValue(AutomationElement.IsPasswordProperty, true);
            if (password is bool isPassword)
            {
                if (isPassword) return new(SelectionStatus.Protected);
                if (depth == 0) focusProtectionProven = true;
            }
            else
            {
                var type = current.Current.ControlType;
                // IsPassword may be NotSupported on known document/structural peers. Never
                // substitute false on an Edit/Custom/unknown control or authorize Copy this way.
                bool structural = depth > 0 && (type == ControlType.Window || type == ControlType.Pane || type == ControlType.Group);
                if (!(structural || type == ControlType.Document || type == ControlType.Text)) return new(SelectionStatus.Untrusted);
            }
            context.Add(current);
            if (new IntPtr(current.Current.NativeWindowHandle) == foreground) { foundForeground = true; break; }
            current = TreeWalker.RawViewWalker.GetParent(current);
        }
        if (!foundForeground) return new(SelectionStatus.Untrusted);
        TextPattern? pattern = null;
        foreach (var element in context)
        {
            if (element.TryGetCurrentPattern(TextPattern.Pattern, out object value) && value is TextPattern candidate)
            { pattern = candidate; break; }
        }
        if (!focusStable()) return new(SelectionStatus.ForegroundChanged);
        if (pattern is null) return SelectionRules.Classify(false, true, false, null, focusWindow.ToInt64(),
            focusProtectionProven && focused.Current.NativeWindowHandle == focusWindow.ToInt64() && Native.SafeNativeCopyTarget(focusWindow));
        var selections = pattern.GetSelection();
        var text = new StringBuilder();
        if (selections.Length > 16) return new(SelectionStatus.TooLarge);
        foreach (var selection in selections)
        {
            if (text.Length > 0) text.Append('\n');
            text.Append(selection.GetText(4001 - text.Length));
            if (text.Length > 4000) return new(SelectionStatus.TooLarge);
        }
        return SelectionRules.Classify(false, focusStable(), true, text.ToString(), focusWindow.ToInt64());
    }

    internal static async Task<SelectionResult> CaptureAsync(IntPtr foreground, CancellationToken cancellationToken)
    {
        try
        {
            var result = await HelperProcess.RunAsync<SelectionResult>("--selection-worker",
                new[] { foreground.ToInt64().ToString(System.Globalization.CultureInfo.InvariantCulture) },
                32768, TimeSpan.FromSeconds(2), cancellationToken);
            if (result is null || !Enum.IsDefined(result.Status)) return new(SelectionStatus.Failed);
            if (result.Status is SelectionStatus.Success or SelectionStatus.Unsupported)
            {
                if (!Native.Stable(foreground, new IntPtr(result.FocusWindow))) return new(SelectionStatus.ForegroundChanged);
                if (result.Status == SelectionStatus.Success && (string.IsNullOrWhiteSpace(result.Text) || result.Text.Length > 4000))
                    return new(SelectionStatus.Failed);
                if (result.Status == SelectionStatus.Unsupported && result.Text is not null) return new(SelectionStatus.Failed);
            }
            return result;
        }
        catch (HelperTimeoutException) { return new(SelectionStatus.Timeout); }
        catch (OperationCanceledException) { throw; }
        catch (Exception) { return new(SelectionStatus.Failed); }
    }
}
