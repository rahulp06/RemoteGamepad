using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Text;
using Nefarius.ViGEm.Client;
using Nefarius.ViGEm.Client.Targets;
using Nefarius.ViGEm.Client.Targets.Xbox360;
using QRCoder;

class Program
{
    static ViGEmClient vigemClient = new ViGEmClient();

    static Dictionary<string, IXbox360Controller> players =
        new Dictionary<string, IXbox360Controller>();

    static int playerCount = 0;

    static void Main()
    {
        string ip = GetLocalIPAddress();

        int port = 5000;

        Console.WriteLine($"🌐 UDP Server: {ip}:{port}");

        GenerateQRCode($"GAMEPAD|{ip}|{port}");

        UdpClient server = new UdpClient(port);

        Console.WriteLine("📡 Waiting for UDP players...");

        while (true)
        {
            IPEndPoint remoteEP = new IPEndPoint(IPAddress.Any, 0);

            byte[] data = server.Receive(ref remoteEP);

            string message = Encoding.UTF8.GetString(data).Trim();

            string playerId = remoteEP.ToString();

            if (!players.ContainsKey(playerId))
            {
                playerCount++;

                var controller = vigemClient.CreateXbox360Controller();

                controller.Connect();

                players[playerId] = controller;

                Console.WriteLine($"🎮 Player {playerCount} connected ({playerId})");
            }

            HandleInput(message, players[playerId]);
        }
    }

    static void HandleInput(string data, IXbox360Controller controller)
    {
        try
        {
            // BUTTONS
            if (data.EndsWith("_DOWN") || data.EndsWith("_UP"))
            {
                bool pressed = data.EndsWith("_DOWN");

                string key = data.Substring(0, data.LastIndexOf('_'));

                switch (key)
                {
                    case "A":
                        controller.SetButtonState(Xbox360Button.A, pressed);
                        break;

                    case "B":
                        controller.SetButtonState(Xbox360Button.B, pressed);
                        break;

                    case "X":
                        controller.SetButtonState(Xbox360Button.X, pressed);
                        break;

                    case "Y":
                        controller.SetButtonState(Xbox360Button.Y, pressed);
                        break;

                    case "LB":
                        controller.SetButtonState(Xbox360Button.LeftShoulder, pressed);
                        break;

                    case "RB":
                        controller.SetButtonState(Xbox360Button.RightShoulder, pressed);
                        break;

                    case "LS":
                        controller.SetButtonState(Xbox360Button.LeftThumb, pressed);
                        break;

                    case "RS":
                        controller.SetButtonState(Xbox360Button.RightThumb, pressed);
                        break;

                    case "START":
                        controller.SetButtonState(Xbox360Button.Start, pressed);
                        break;

                    case "SELECT":
                        controller.SetButtonState(Xbox360Button.Back, pressed);
                        break;

                    case "DPAD_UP":
                        controller.SetButtonState(Xbox360Button.Up, pressed);
                        break;

                    case "DPAD_DOWN":
                        controller.SetButtonState(Xbox360Button.Down, pressed);
                        break;

                    case "DPAD_LEFT":
                        controller.SetButtonState(Xbox360Button.Left, pressed);
                        break;

                    case "DPAD_RIGHT":
                        controller.SetButtonState(Xbox360Button.Right, pressed);
                        break;

                    case "LT":
                        controller.SetSliderValue(
                            Xbox360Slider.LeftTrigger,
                            pressed ? (byte)255 : (byte)0
                        );
                        break;

                    case "RT":
                        controller.SetSliderValue(
                            Xbox360Slider.RightTrigger,
                            pressed ? (byte)255 : (byte)0
                        );
                        break;
                }

                controller.SubmitReport();
            }

            // JOYSTICKS
            else if (data.StartsWith("JOY_"))
            {
                var parts = data.Split(':');

                if (parts.Length < 2)
                    return;

                var tag = parts[0];

                var values = parts[1].Split(',');

                if (values.Length < 2)
                    return;

                float x = float.Parse(values[0]);

                float y = float.Parse(values[1]);

                Console.WriteLine($"[{tag}] X={x} Y={y}");

                short joyX = (short)(x * 32767);

                short joyY = (short)(-y * 32767);

                if (tag == "JOY_L")
                {
                    controller.SetAxisValue(Xbox360Axis.LeftThumbX, joyX);
                    controller.SetAxisValue(Xbox360Axis.LeftThumbY, joyY);
                }
                else if (tag == "JOY_R")
                {
                    controller.SetAxisValue(Xbox360Axis.RightThumbX, joyX);
                    controller.SetAxisValue(Xbox360Axis.RightThumbY, joyY);
                }

                controller.SubmitReport();
            }
        }
        catch
        {
            // Ignore malformed packets
        }
    }

    static string GetLocalIPAddress()
    {
        foreach (var ip in Dns.GetHostEntry(Dns.GetHostName()).AddressList)
        {
            if (ip.AddressFamily == AddressFamily.InterNetwork)
            {
                return ip.ToString();
            }
        }

        return "127.0.0.1";
    }

    static void GenerateQRCode(string text)
    {
        QRCodeGenerator qrGenerator = new QRCodeGenerator();

        QRCodeData qrCodeData =
            qrGenerator.CreateQrCode(text, QRCodeGenerator.ECCLevel.Q);

        PngByteQRCode qrCode = new PngByteQRCode(qrCodeData);

        byte[] qrBytes = qrCode.GetGraphic(20);

        string path = "server_qr.png";

        System.IO.File.WriteAllBytes(path, qrBytes);

        Console.WriteLine("🖼 QR generated");

        Process.Start(new ProcessStartInfo(path)
        {
            UseShellExecute = true
        });
    }
}
