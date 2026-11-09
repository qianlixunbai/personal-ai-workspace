using System;
using System.Text;

namespace PersonalAiWorkspace.Desktop;

internal static class PlainTextMarkdown
{
    internal static void Block(StringBuilder note, string text)
    {
        // A longer fence keeps user/model/source Markdown, HTML and links inert.
        int longest = 0, run = 0;
        foreach (char c in text) { run = c == '`' ? run + 1 : 0; longest = Math.Max(longest, run); }
        string fence = new('`', Math.Max(3, longest + 1));
        note.Append(fence).Append("text\n").Append(text);
        if (!text.EndsWith('\n')) note.Append('\n');
        note.Append(fence).Append("\n\n");
    }
}
