namespace RemoteGamepadServer;

using System;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Timers;

/// <summary>
/// Receives UDP datagrams containing controller input state.
///
/// Each datagram is a single UTF-8 JSON payload (same schema as the Bluetooth
/// INPUT message, but without the "INPUT|" prefix — a datagram doesn't need
/// message-type tagging).
///
/// Single-client policy: only accepts packets from the most recent valid source.
/// No retransmission, ordering, or acknowledgement — just apply the latest state.
/// </summary>
public class UdpInputServer : IDisposable
{
    public const int INPUT_PORT = 26760;

    private UdpClient? _udpClient;
    private CancellationTokenSource? _cts;
    private Task? _listenTask;

    /// <summary>
    /// The last endpoint that sent a valid input packet.
    /// Only packets from this endpoint are accepted (single-client policy).
    /// </summary>
    private IPEndPoint? _activeClient;

    /// <summary>
    /// Timestamp (UTC) of the last valid input packet received. Used by the
    /// stale-input watchdog to detect a vanished client and reset the controller.
    /// </summary>
    private DateTime _lastReceiveUtc = DateTime.MinValue;

    /// <summary>
    /// If no valid input packet arrives for this long, the client is considered gone.
    /// Android sends Wi-Fi input at ~60 Hz (~16 ms), so 500 ms is very tolerant.
    /// </summary>
    private readonly int _staleTimeoutMs = 500;

    /// <summary>
    /// Lightweight liveness watchdog. Fires ~every 200 ms and resets the client
    /// if no valid packet has arrived within <see cref="_staleTimeoutMs"/>.
    /// </summary>
    private System.Timers.Timer? _watchdog;

    public bool IsListening => _udpClient != null;

    /// <summary>
    /// Fired when a valid controller state is received and parsed.
    /// </summary>
    public event EventHandler<InputParser.ControllerState>? InputReceived;

    public event EventHandler<string>? ClientConnected;
    public event EventHandler? ClientDisconnected;
    public event EventHandler<string>? Error;

    /// <summary>
    /// Start listening for UDP input on the configured port.
    /// </summary>
    public void Start()
    {
        if (_udpClient != null)
            throw new InvalidOperationException("UDP input server already started");

        _udpClient = new UdpClient();
        _udpClient.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
        _udpClient.Client.Bind(new IPEndPoint(IPAddress.Any, INPUT_PORT));

        _cts = new CancellationTokenSource();
        _listenTask = Task.Run(() => ListenLoop(_cts.Token));

        // Start the stale-input watchdog (~200 ms cadence).
        _lastReceiveUtc = DateTime.MinValue;
        _watchdog = new System.Timers.Timer(200);
        _watchdog.AutoReset = true;
        _watchdog.Elapsed += (_, _) => WatchdogTick();
        _watchdog.Start();

        Console.WriteLine($"[WIFI] UDP input server listening on port {INPUT_PORT}");
    }

    private void ListenLoop(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            try
            {
                var remoteEp = new IPEndPoint(IPAddress.Any, 0);
                var data = _udpClient!.Receive(ref remoteEp);

                // Skip non-UTF8 data
                string json;
                try
                {
                    json = Encoding.UTF8.GetString(data);
                }
                catch
                {
                    continue;
                }

                // Parse the input
                var state = InputParser.ParseUdp(json);
                if (!state.HasValue)
                    continue;

                _lastReceiveUtc = DateTime.UtcNow;

                // Single-client policy: accept from first (or most recent) source
                if (_activeClient == null)
                {
                    _activeClient = remoteEp;
                    _lastReceiveUtc = DateTime.UtcNow;
                    var clientIp = remoteEp.Address.ToString();
                    ClientConnected?.Invoke(this, clientIp);
                    Console.WriteLine($"[WIFI] Client connected: {clientIp}");
                }
                else if (!_activeClient.Equals(remoteEp))
                {
                    // Different client — accept it (replaces previous)
                    Console.WriteLine($"[WIFI] Client switched: {_activeClient.Address} → {remoteEp.Address}");
                    ClientDisconnected?.Invoke(this, EventArgs.Empty);
                    _activeClient = remoteEp;
                    _lastReceiveUtc = DateTime.UtcNow;
                    ClientConnected?.Invoke(this, remoteEp.Address.ToString());
                }

                InputReceived?.Invoke(this, state.Value);
            }
            catch (SocketException ex) when (ex.SocketErrorCode == SocketError.Interrupted)
            {
                break;
            }
            catch (ObjectDisposedException)
            {
                break;
            }
            catch (Exception ex)
            {
                if (!ct.IsCancellationRequested)
                {
                    Error?.Invoke(this, $"UDP input error: {ex.Message}");
                }
            }
        }
    }

    /// <summary>
    /// Notify the server that the active client disconnected
    /// (e.g., controller overlay closed). Resets single-client state.
    /// </summary>
    public void ResetClient()
    {
        if (_activeClient != null)
        {
            Console.WriteLine($"[WIFI] Client disconnected: {_activeClient.Address}");
            _activeClient = null;
            _lastReceiveUtc = DateTime.MinValue;
            ClientDisconnected?.Invoke(this, EventArgs.Empty);
        }
    }

    /// <summary>
    /// Watchdog invoked ~every 200 ms. If an active client exists but no valid
    /// input packet has arrived within <see cref="_staleTimeoutMs"/>, the client
    /// is treated as gone and ResetClient() is called (which fires
    /// ClientDisconnected so the virtual controller is reset to neutral).
    /// </summary>
    private void WatchdogTick()
    {
        if (_cts?.IsCancellationRequested != false) return;
        if (_activeClient != null &&
            (DateTime.UtcNow - _lastReceiveUtc).TotalMilliseconds > _staleTimeoutMs)
        {
            ResetClient();
        }
    }

    /// <summary>
    /// Stop the server and release resources.
    /// </summary>
    public void Stop()
    {
        _cts?.Cancel();
        try { _watchdog?.Stop(); } catch { }
        try { _watchdog?.Dispose(); } catch { }
        _watchdog = null;
        try { _udpClient?.Close(); } catch { }
        _listenTask?.Wait(TimeSpan.FromSeconds(2));
        _udpClient = null;
        _activeClient = null;
        _lastReceiveUtc = DateTime.MinValue;
        Console.WriteLine("[WIFI] UDP input server stopped");
    }

    public void Dispose()
    {
        Stop();
        _cts?.Dispose();
    }
}
