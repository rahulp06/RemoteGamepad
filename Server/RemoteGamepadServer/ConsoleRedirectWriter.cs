using System;
using System.IO;
using System.Text;
using System.Windows.Threading;

namespace RemoteGamepadServer;

/// <summary>
/// A TextWriter that forwards all Console.Out/Console.Error output
/// to a WPF Dispatcher callback, enabling thread-safe GUI log display.
///
/// Backend classes call Console.WriteLine from background threads.
/// This writer captures those calls and marshals them to the WPF UI thread
/// via Dispatcher.BeginInvoke.
/// </summary>
public class ConsoleRedirectWriter : TextWriter
{
    private readonly Action<string> _onWrite;
    private readonly object _lock = new();

    public ConsoleRedirectWriter(Action<string> onWrite)
    {
        _onWrite = onWrite;
    }

    public override void Write(char[]? buffer, int index, int count)
    {
        if (buffer == null || count == 0) return;
        var text = new string(buffer, index, count);
        Emit(text);
    }

    public override void Write(string? value)
    {
        if (value == null) return;
        Emit(value);
    }

    public override void WriteLine(string? value)
    {
        Emit(value ?? "");
        Emit(Environment.NewLine);
    }

    public override void WriteLine()
    {
        Emit(Environment.NewLine);
    }

    public override Encoding Encoding => Encoding.UTF8;

    private void Emit(string text)
    {
        lock (_lock)
        {
            _onWrite(text);
        }
    }
}
