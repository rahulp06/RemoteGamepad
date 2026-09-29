using System;
using System.IO;
using System.Net;
using System.Windows;
using System.Windows.Media;
using InTheHand.Net.Bluetooth;

namespace RemoteGamepadServer;

public partial class MainWindow : Window
{
    // ── Backend instances (kept alive for app lifetime) ──
    private VirtualController? _virtualController;
    private BluetoothServer? _btServer;
    private UdpInputServer? _udpInputServer;
    private UdpDiscoveryListener? _udpDiscovery;

    // ── State ──
    private bool _serverRunning;
    private string _serverName = Dns.GetHostName();
    private BluetoothRadio? _radio;

    // ── Client tracking ──
    private bool _btClientConnected;
    private bool _wifiClientConnected;
    private string _connectedClientInfo = "";
    private int _wifiInputCount;
    private int _btInputCount;

    // ── Active transport arbitration (only one transport may drive ViGEm) ──
    private enum ActiveTransport { None, Bluetooth, Wifi }
    private ActiveTransport _activeTransport = ActiveTransport.None;
    private readonly object _transportLock = new();

    // ── Console redirect ──
    private TextWriter? _originalOut;
    private TextWriter? _originalError;

    // ── Colors ──
    private static readonly Brush Green = new SolidColorBrush(Color.FromRgb(0x2E, 0xCC, 0x71));
    private static readonly Brush Red = new SolidColorBrush(Color.FromRgb(0xE7, 0x4C, 0x3C));
    private static readonly Brush Gray = new SolidColorBrush(Color.FromRgb(0x55, 0x5A, 0x6A));
    private static readonly Brush DimGreen = new SolidColorBrush(Color.FromRgb(0x1A, 0x3A, 0x2C));

    public bool IsServerRunning => _serverRunning;

    public MainWindow()
    {
        InitializeComponent();

        // Redirect console output to the GUI log
        SetupConsoleRedirect();

        // Auto-start the server when the window opens
        Loaded += (_, _) => StartServer();
        // Clean shutdown when the window closes
        Closing += MainWindow_Closing;

        UpdateUI();
    }

    // ══════════════════════════════════════════════
    //  CONSOLE REDIRECT
    // ══════════════════════════════════════════════

    private void SetupConsoleRedirect()
    {
        _originalOut = Console.Out;
        _originalError = Console.Error;

        var writer = new ConsoleRedirectWriter(msg =>
        {
            Dispatcher.BeginInvoke(() =>
            {
                LogBox.AppendText(msg);
                LogScroll.ScrollToEnd();
            });
        });

        Console.SetOut(writer);
        Console.SetError(writer);
    }

    private void RestoreConsole()
    {
        if (_originalOut != null) Console.SetOut(_originalOut);
        if (_originalError != null) Console.SetError(_originalError);
    }

    // ══════════════════════════════════════════════
    //  START SERVER
    // ══════════════════════════════════════════════

    public void StartServer()
    {
        if (_serverRunning) return;

        try
        {
            // ── Banner ──
            Console.WriteLine("============================================");
            Console.WriteLine("  RemoteGamepad Server — Milestone 3");
            Console.WriteLine("  Bluetooth Classic (RFCOMM/SPP)");
            Console.WriteLine("  Wi-Fi UDP Input (port 26760)");
            Console.WriteLine("  Virtual Xbox 360 Controller (ViGEm)");
            Console.WriteLine("============================================");
            Console.WriteLine();

            // ── Server name ──
            _serverName = Dns.GetHostName();
            Console.WriteLine($"Server name: {_serverName}");

            // ── 1. Virtual controller ──
            _virtualController = new VirtualController();
            _virtualController.Initialize();
            Console.WriteLine();

            // ── 2. Bluetooth server ──
            _radio = BluetoothRadio.Default;
            _btServer = new BluetoothServer();

            if (_radio != null)
            {
                Console.WriteLine($"Bluetooth Radio: {_radio.Name}");
                Console.WriteLine($"Radio Mode: {_radio.Mode}");
                Console.WriteLine($"Local Address: {_radio.LocalAddress}");
            }
            else
            {
                Console.WriteLine("[WARN] No Bluetooth radio found. Bluetooth input will be unavailable.");
            }
            Console.WriteLine();

            // ── 3. Wi-Fi servers ──
            _udpInputServer = new UdpInputServer();
            _udpDiscovery = new UdpDiscoveryListener(_serverName, UdpInputServer.INPUT_PORT);

            // ── Wire Bluetooth events ──
            _btServer.Connected += (_, deviceName) =>
            {
                lock (_transportLock)
                {
                    if (_activeTransport == ActiveTransport.Wifi)
                        _udpInputServer?.ResetClient();
                    _activeTransport = ActiveTransport.Bluetooth;
                }
                _btClientConnected = true;
                _connectedClientInfo = deviceName;
                Console.WriteLine($"[BT] Connected: {deviceName}");
                Dispatcher.BeginInvoke(UpdateUI);
            };

            _btServer.Disconnected += (_, _) =>
            {
                bool wasActive;
                lock (_transportLock)
                {
                    wasActive = _activeTransport == ActiveTransport.Bluetooth;
                    if (wasActive) _activeTransport = ActiveTransport.None;
                }
                _btClientConnected = false;
                _connectedClientInfo = "";
                Console.WriteLine("[BT] Disconnected" + (wasActive ? " — resetting virtual controller" : ""));
                if (wasActive) _virtualController?.Reset();
                Dispatcher.BeginInvoke(UpdateUI);
            };

            _btServer.InputReceived += (_, state) =>
            {
                if (_activeTransport != ActiveTransport.Bluetooth) return;
                _btInputCount++;
                _virtualController?.Update(state);
                if (_btInputCount <= 3 || _btInputCount % 600 == 0)
                    Console.WriteLine($"[BT INPUT] #{_btInputCount}: lx={state.Lx:F2} ly={state.Ly:F2} a={state.A} b={state.B}");
            };

            _btServer.Error += (_, err) => Console.WriteLine($"[BT] Error: {err}");

            // ── Wire Wi-Fi events ──
            _udpInputServer.InputReceived += (_, state) =>
            {
                if (_activeTransport != ActiveTransport.Wifi) return;
                _wifiInputCount++;
                _virtualController?.Update(state);
                if (_wifiInputCount <= 3 || _wifiInputCount % 600 == 0)
                    Console.WriteLine($"[WIFI INPUT] #{_wifiInputCount}: lx={state.Lx:F2} ly={state.Ly:F2} a={state.A} b={state.B}");
            };

            _udpInputServer.ClientConnected += (_, clientIp) =>
            {
                lock (_transportLock)
                {
                    if (_activeTransport == ActiveTransport.Bluetooth)
                        _btServer?.DisconnectClient();
                    _activeTransport = ActiveTransport.Wifi;
                }
                _wifiClientConnected = true;
                _connectedClientInfo = clientIp;
                Console.WriteLine($"[WIFI] Controller client connected: {clientIp}");
                Dispatcher.BeginInvoke(UpdateUI);
            };

            _udpInputServer.ClientDisconnected += (_, _) =>
            {
                bool wasActive;
                lock (_transportLock)
                {
                    wasActive = _activeTransport == ActiveTransport.Wifi;
                    if (wasActive) _activeTransport = ActiveTransport.None;
                }
                _wifiClientConnected = false;
                _connectedClientInfo = "";
                Console.WriteLine("[WIFI] Controller client disconnected" + (wasActive ? " — resetting virtual controller" : ""));
                if (wasActive) _virtualController?.Reset();
                Dispatcher.BeginInvoke(UpdateUI);
            };

            _udpInputServer.Error += (_, err) => Console.WriteLine($"[WIFI] Error: {err}");
            _udpDiscovery.Error += (_, err) => Console.WriteLine($"[WIFI] Discovery error: {err}");

            // ── 4. Start all servers ──
            if (_radio != null)
            {
                _btServer.Start();
            }

            _udpInputServer.Start();
            _udpDiscovery.Start();

            Console.WriteLine();
            Console.WriteLine($"Wi-Fi input port: {UdpInputServer.INPUT_PORT}");
            Console.WriteLine($"Wi-Fi discovery port: {UdpDiscoveryListener.DISCOVERY_PORT}");
            Console.WriteLine();

            _serverRunning = true;
            Console.WriteLine("[SERVER] Server started successfully.");
        }
        catch (Exception ex)
        {
            Console.WriteLine($"[SERVER] Failed to start: {ex.Message}");
            StopServer();
        }

        UpdateUI();
    }

    // ══════════════════════════════════════════════
    //  STOP SERVER
    // ══════════════════════════════════════════════

    public void StopServer()
    {
        if (!_serverRunning && _virtualController == null) return;

        Console.WriteLine("[SERVER] Shutting down gracefully...");

        try { _btServer?.Stop(); } catch { }
        try { _udpInputServer?.Stop(); } catch { }
        try { _udpDiscovery?.Stop(); } catch { }

        try { _virtualController?.Reset(); } catch { }
        try { _virtualController?.Dispose(); } catch { }

        _btServer = null;
        _udpInputServer = null;
        _udpDiscovery = null;
        _virtualController = null;
        _radio = null;
        _btClientConnected = false;
        _wifiClientConnected = false;
        _connectedClientInfo = "";
        _wifiInputCount = 0;
        _btInputCount = 0;
        _serverRunning = false;

        Console.WriteLine("[SERVER] Goodbye.");
        UpdateUI();
    }

    // ══════════════════════════════════════════════
    //  DISCONNECT CLIENT
    // ══════════════════════════════════════════════

    public void DisconnectClient()
    {
        _btServer?.DisconnectClient();
        _udpInputServer?.ResetClient();
    }

    // ══════════════════════════════════════════════
    //  UI STATE UPDATE
    // ══════════════════════════════════════════════

    private void UpdateUI()
    {
        // ── Server status ──
        if (_serverRunning)
        {
            StatusDot.Fill = Green;
            StatusText.Text = "RUNNING";
            StatusText.Foreground = Green;
        }
        else
        {
            StatusDot.Fill = Red;
            StatusText.Text = "STOPPED";
            StatusText.Foreground = Red;
        }

        // ── Bluetooth ──
        if (_serverRunning && _radio != null)
        {
            BtDot.Fill = Green;
            BtStatus.Text = "Listening";
            BtStatus.Foreground = Green;
            BtRadio.Text = $"Radio: {_radio.Name}";
            BtAddress.Text = $"Address: {_radio.LocalAddress}";
        }
        else if (_serverRunning && _radio == null)
        {
            BtDot.Fill = Red;
            BtStatus.Text = "No radio found";
            BtStatus.Foreground = Red;
            BtRadio.Text = "Radio: —";
            BtAddress.Text = "Address: —";
        }
        else
        {
            BtDot.Fill = Gray;
            BtStatus.Text = "Stopped";
            BtStatus.Foreground = Gray;
            BtRadio.Text = "Radio: —";
            BtAddress.Text = "Address: —";
        }

        // ── Wi-Fi ──
        if (_serverRunning)
        {
            WifiDot.Fill = Green;
            WifiStatus.Text = "Listening";
            WifiStatus.Foreground = Green;
        }
        else
        {
            WifiDot.Fill = Gray;
            WifiStatus.Text = "Stopped";
            WifiStatus.Foreground = Gray;
        }

        // ── Virtual controller ──
        if (_virtualController != null && _virtualController.IsAvailable)
        {
            VcDot.Fill = Green;
            VcStatus.Text = "Xbox 360 — Connected";
            VcStatus.Foreground = Green;
        }
        else if (_serverRunning)
        {
            VcDot.Fill = Red;
            VcStatus.Text = "Unavailable";
            VcStatus.Foreground = Red;
        }
        else
        {
            VcDot.Fill = Gray;
            VcStatus.Text = "Disconnected";
            VcStatus.Foreground = Gray;
        }

        // ── Connected client ──
        if (_btClientConnected)
        {
            ClientDot.Fill = Green;
            ClientStatus.Text = "Connected";
            ClientStatus.Foreground = Green;
            ClientTransport.Text = $"Bluetooth\n{_connectedClientInfo}";
        }
        else if (_wifiClientConnected)
        {
            ClientDot.Fill = Green;
            ClientStatus.Text = "Connected";
            ClientStatus.Foreground = Green;
            ClientTransport.Text = $"Wi-Fi UDP\n{_connectedClientInfo}";
        }
        else
        {
            ClientDot.Fill = Gray;
            ClientStatus.Text = "No client connected";
            ClientStatus.Foreground = Gray;
            ClientTransport.Text = "";
        }

        // ── Button states ──
        BtnStart.IsEnabled = !_serverRunning;
        BtnStop.IsEnabled = _serverRunning;
        BtnDisconnect.IsEnabled = _btClientConnected || _wifiClientConnected;
    }

    // ══════════════════════════════════════════════
    //  WINDOW CLOSE
    // ══════════════════════════════════════════════

    private void MainWindow_Closing(object? sender, System.ComponentModel.CancelEventArgs e)
    {
        StopServer();
        RestoreConsole();
    }

    // ══════════════════════════════════════════════
    //  BUTTON HANDLERS
    // ══════════════════════════════════════════════

    private void BtnStart_Click(object sender, RoutedEventArgs e)
    {
        StartServer();
    }

    private void BtnStop_Click(object sender, RoutedEventArgs e)
    {
        StopServer();
    }

    private void BtnDisconnect_Click(object sender, RoutedEventArgs e)
    {
        DisconnectClient();
    }
}
