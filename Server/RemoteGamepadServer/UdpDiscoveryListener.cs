namespace RemoteGamepadServer;

using System;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

/// <summary>
/// Listens for UDP broadcast discovery requests on the LAN and responds
/// with server identity information. Runs alongside the Bluetooth server.
///
/// Protocol:
///   Client sends:  "REMOTE_GAMEPAD_DISCOVER"
///   Server replies: "REMOTE_GAMEPAD_SERVER|{serverName}|{inputPort}"
///
/// Listens on all local interfaces, port 26761 (one above the input port).
/// </summary>
public class UdpDiscoveryListener : IDisposable
{
    public const string DISCOVER_REQUEST = "REMOTE_GAMEPAD_DISCOVER";
    public const string DISCOVER_RESPONSE_PREFIX = "REMOTE_GAMEPAD_SERVER";
    public const int DISCOVERY_PORT = 26761;

    private readonly int _inputPort;
    private readonly string _serverName;
    private UdpClient? _udpClient;
    private CancellationTokenSource? _cts;
    private Task? _listenTask;

    public event EventHandler<string>? Error;

    public UdpDiscoveryListener(string serverName, int inputPort)
    {
        _serverName = serverName;
        _inputPort = inputPort;
    }

    /// <summary>
    /// Start listening for discovery broadcasts.
    /// </summary>
    public void Start()
    {
        if (_udpClient != null)
            throw new InvalidOperationException("Discovery listener already started");

        _udpClient = new UdpClient();
        _udpClient.EnableBroadcast = true;
        // Allow multiple instances on the same port (e.g. during dev)
        _udpClient.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
        _udpClient.Client.Bind(new IPEndPoint(IPAddress.Any, DISCOVERY_PORT));

        _cts = new CancellationTokenSource();
        _listenTask = Task.Run(() => ListenLoop(_cts.Token));

        Console.WriteLine($"[WIFI] Discovery listener started on UDP port {DISCOVERY_PORT}");
    }

    private void ListenLoop(CancellationToken ct)
    {
        var responseBytes = Encoding.UTF8.GetBytes($"{DISCOVER_RESPONSE_PREFIX}|{_serverName}|{_inputPort}");

        while (!ct.IsCancellationRequested)
        {
            try
            {
                var remoteEp = new IPEndPoint(IPAddress.Any, 0);
                var data = _udpClient!.Receive(ref remoteEp);
                var message = Encoding.UTF8.GetString(data);

                if (message == DISCOVER_REQUEST)
                {
                    Console.WriteLine($"[WIFI] Discovery request from {remoteEp}");
                    _udpClient.Send(responseBytes, responseBytes.Length, remoteEp);
                }
            }
            catch (SocketException ex) when (ex.SocketErrorCode == SocketError.Interrupted)
            {
                // Expected on shutdown
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
                    Error?.Invoke(this, $"Discovery listen error: {ex.Message}");
                    Console.WriteLine($"[WIFI] Discovery error: {ex.Message}");
                }
            }
        }
    }

    /// <summary>
    /// Stop the listener and release resources.
    /// </summary>
    public void Stop()
    {
        _cts?.Cancel();
        try { _udpClient?.Close(); } catch { }
        _listenTask?.Wait(TimeSpan.FromSeconds(2));
        _udpClient = null;
        Console.WriteLine("[WIFI] Discovery listener stopped");
    }

    public void Dispose()
    {
        Stop();
        _cts?.Dispose();
    }
}
