using System.Diagnostics;
using System.Drawing.Drawing2D;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using Microsoft.Win32;

namespace DropFly;

static class Program
{
    [STAThread]
    static void Main()
    {
        using var mutex = new Mutex(true, "DropFly.SingleInstance", out bool first);
        if (!first) return;
        ApplicationConfiguration.Initialize();
        Application.Run(new TrayApp());
    }
}

// ---------------------------------------------------------------- config

static class Config
{
    static readonly string Dir =
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "DropFly");
    static string CodeFile => Path.Combine(Dir, "code.txt");

    public static string Code { get; private set; } = "";
    public static bool IsFirstRun { get; private set; }

    public static string SaveDir =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads", "DropFly");

    // half-received files live here until they are complete (so a dropped connection can resume)
    public static string PartDir => Path.Combine(SaveDir, ".parts");

    public static void Load()
    {
        Directory.CreateDirectory(Dir);
        if (File.Exists(CodeFile)) Code = File.ReadAllText(CodeFile).Trim();
        if (Code.Length == 0)
        {
            Code = RandomNumberGenerator.GetInt32(100000, 1000000).ToString();
            File.WriteAllText(CodeFile, Code);
            IsFirstRun = true;
        }
        Directory.CreateDirectory(SaveDir);
        Directory.CreateDirectory(PartDir);
        try
        {
            foreach (var f in Directory.GetFiles(PartDir, "*.part"))
                if (File.GetLastWriteTimeUtc(f) < DateTime.UtcNow.AddDays(-3)) File.Delete(f);
        }
        catch { }
    }

    const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";

    public static bool StartWithWindows
    {
        get
        {
            using var k = Registry.CurrentUser.OpenSubKey(RunKey);
            return k?.GetValue("DropFly") != null;
        }
        set
        {
            using var k = Registry.CurrentUser.OpenSubKey(RunKey, true);
            if (k == null) return;
            if (value) k.SetValue("DropFly", "\"" + Environment.ProcessPath + "\"");
            else k.DeleteValue("DropFly", false);
        }
    }
}

// ---------------------------------------------------------------- tray app

sealed class TrayApp : ApplicationContext
{
    readonly NotifyIcon tray;
    readonly Control ui = new();

    public TrayApp()
    {
        Config.Load();
        ui.CreateControl();
        _ = ui.Handle; // force handle so we can BeginInvoke from server threads

        if (Config.IsFirstRun) Config.StartWithWindows = true;

        var menu = new ContextMenuStrip();
        var codeItem = new ToolStripMenuItem("Pairing code: " + Config.Code) { Enabled = false };
        menu.Items.Add(codeItem);
        menu.Items.Add("Open received files", null, (_, _) =>
            Process.Start(new ProcessStartInfo("explorer.exe", "\"" + Config.SaveDir + "\"") { UseShellExecute = true }));
        var startup = new ToolStripMenuItem("Start with Windows") { Checked = Config.StartWithWindows, CheckOnClick = true };
        startup.CheckedChanged += (_, _) => Config.StartWithWindows = startup.Checked;
        menu.Items.Add(startup);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Exit", null, (_, _) => { tray!.Visible = false; Application.Exit(); });

        tray = new NotifyIcon
        {
            Icon = MakeIcon(),
            Text = "DropFly - ready",
            ContextMenuStrip = menu,
            Visible = true,
        };

        var server = new Server(
            begin: (title, sub) => ui.BeginInvoke(new Action(() => Overlay.Begin(title, sub))),
            progress: f => ui.BeginInvoke(new Action(() => Overlay.Progress(f))),
            finish: (ok, sub, path) => ui.BeginInvoke(new Action(() => Overlay.Finish(ok, sub, path))),
            setClipboard: text =>
            {
                ui.Invoke(new Action(() => { try { Clipboard.SetText(text); } catch { } }));
            });
        server.Start();

        if (Config.IsFirstRun)
        {
            MessageBox.Show(
                "DropFly is running in your system tray.\n\nYour pairing code is:\n\n        " + Config.Code +
                "\n\nEnter this code once in the DropFly app on your phone.\n" +
                "(You can see it again any time by right-clicking the tray icon.)\n\n" +
                "If Windows asks about the firewall, allow it on Private networks.",
                "DropFly", MessageBoxButtons.OK, MessageBoxIcon.Information);
        }
    }

    static Icon MakeIcon()
    {
        using var bmp = new Bitmap(32, 32);
        using (var g = Graphics.FromImage(bmp))
        {
            g.SmoothingMode = SmoothingMode.AntiAlias;
            using var br = new LinearGradientBrush(new Rectangle(0, 0, 32, 32),
                Color.FromArgb(94, 160, 255), Color.FromArgb(140, 90, 255), 45f);
            g.FillEllipse(br, 1, 1, 30, 30);
            using var pen = new Pen(Color.White, 3f) { StartCap = LineCap.Round, EndCap = LineCap.Round };
            g.DrawLine(pen, 16, 23, 16, 9);
            g.DrawLine(pen, 10, 15, 16, 9);
            g.DrawLine(pen, 22, 15, 16, 9);
        }
        return Icon.FromHandle(bmp.GetHicon());
    }
}

// ---------------------------------------------------------------- server

sealed class Server
{
    const int HttpPort = 53317;
    const int DiscoveryPort = 53318;
    static readonly Regex IdRx = new("^[0-9a-f]{32}$", RegexOptions.Compiled);

    readonly Action<string, string> begin;
    readonly Action<double> progress;
    readonly Action<bool, string, string?> finish;
    readonly Action<string> setClipboard;

    public Server(Action<string, string> begin, Action<double> progress,
                  Action<bool, string, string?> finish, Action<string> setClipboard)
    {
        this.begin = begin; this.progress = progress; this.finish = finish; this.setClipboard = setClipboard;
    }

    public void Start()
    {
        new Thread(DiscoveryLoop) { IsBackground = true }.Start();
        new Thread(HttpLoop) { IsBackground = true }.Start();
    }

    // The phone broadcasts "DROPFLY?" on the Wi-Fi; we answer with our port.
    void DiscoveryLoop()
    {
        while (true)
        {
            try
            {
                using var udp = new UdpClient(new IPEndPoint(IPAddress.Any, DiscoveryPort));
                var ep = new IPEndPoint(IPAddress.Any, 0);
                while (true)
                {
                    var data = udp.Receive(ref ep);
                    if (Encoding.UTF8.GetString(data) == "DROPFLY?")
                    {
                        var reply = Encoding.UTF8.GetBytes($"DROPFLY!{HttpPort}|{Environment.MachineName}");
                        udp.Send(reply, reply.Length, ep);
                    }
                }
            }
            catch { Thread.Sleep(1000); }
        }
    }

    void HttpLoop()
    {
        while (true)
        {
            try
            {
                var l = new TcpListener(IPAddress.Any, HttpPort);
                l.Start();
                while (true)
                {
                    var c = l.AcceptTcpClient();
                    Task.Run(() => Handle(c));
                }
            }
            catch { Thread.Sleep(1000); }
        }
    }

    static void Reply(Stream s, int code, string text)
    {
        string reason = code switch { 200 => "OK", 400 => "Bad Request", 401 => "Unauthorized", 404 => "Not Found", 409 => "Conflict", 413 => "Payload Too Large", _ => "Error" };
        var body = Encoding.UTF8.GetBytes(text);
        var head = $"HTTP/1.1 {code} {reason}\r\nContent-Length: {body.Length}\r\nConnection: close\r\n\r\n";
        var h = Encoding.ASCII.GetBytes(head);
        s.Write(h, 0, h.Length);
        s.Write(body, 0, body.Length);
        s.Flush();
    }

    static string? ReadHeaderBlock(Stream s, out byte[] leftover)
    {
        var buf = new byte[16384];
        int len = 0;
        leftover = Array.Empty<byte>();
        while (len < buf.Length)
        {
            int n = s.Read(buf, len, buf.Length - len);
            if (n <= 0) return null;
            len += n;
            for (int i = Math.Max(0, len - n - 3); i + 3 < len; i++)
            {
                if (buf[i] == 13 && buf[i + 1] == 10 && buf[i + 2] == 13 && buf[i + 3] == 10)
                {
                    leftover = buf[(i + 4)..len];
                    return Encoding.ASCII.GetString(buf, 0, i);
                }
            }
        }
        return null;
    }

    static string PartPath(string id) => Path.Combine(Config.PartDir, id + ".part");

    static string QueryValue(string query, string key)
    {
        foreach (var kv in query.Split('&'))
            if (kv.StartsWith(key + "="))
                return Uri.UnescapeDataString(kv[(key.Length + 1)..].Replace('+', ' '));
        return "";
    }

    void Handle(TcpClient c)
    {
        bool started = false, completed = false;
        try
        {
            using (c)
            {
                c.ReceiveTimeout = 30000;
                c.SendTimeout = 30000;
                c.ReceiveBufferSize = 1 << 20;
                c.NoDelay = true;
                using var ns = c.GetStream();

                var head = ReadHeaderBlock(ns, out var leftover);
                if (head == null) return;

                var lines = head.Split("\r\n");
                var first = lines[0].Split(' ');
                if (first.Length < 2) return;
                string method = first[0], target = first[1];
                var headers = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
                foreach (var line in lines.Skip(1))
                {
                    int i = line.IndexOf(':');
                    if (i > 0) headers[line[..i].Trim()] = line[(i + 1)..].Trim();
                }

                if (!headers.TryGetValue("X-Token", out var tok) || tok != Config.Code)
                {
                    Reply(ns, 401, "Unauthorized");
                    return;
                }

                string path = target, query = "";
                int q = target.IndexOf('?');
                if (q >= 0) { path = target[..q]; query = target[(q + 1)..]; }

                long length = headers.TryGetValue("Content-Length", out var cl) && long.TryParse(cl, out var clv) ? clv : 0;

                if (path == "/ping") { Reply(ns, 200, "OK"); return; }

                // how many bytes of this transfer do we already have? (lets the phone resume)
                if (path == "/status")
                {
                    var sid = QueryValue(query, "id");
                    long have = IdRx.IsMatch(sid) && File.Exists(PartPath(sid)) ? new FileInfo(PartPath(sid)).Length : 0;
                    Reply(ns, 200, have.ToString());
                    return;
                }

                if (path == "/text" && method == "POST")
                {
                    if (length > 1_000_000) { Reply(ns, 413, "Too large"); return; }
                    var ms = new MemoryStream();
                    ms.Write(leftover, 0, leftover.Length);
                    var tmp = new byte[8192];
                    while (ms.Length < length)
                    {
                        int n = ns.Read(tmp, 0, (int)Math.Min(tmp.Length, length - ms.Length));
                        if (n <= 0) break;
                        ms.Write(tmp, 0, n);
                    }
                    var text = Encoding.UTF8.GetString(ms.ToArray());
                    begin("Text copied", "Ready to paste");
                    setClipboard(text);
                    finish(true, Preview(text), null);
                    Reply(ns, 200, "OK");
                    return;
                }

                if (path == "/upload" && method == "POST")
                {
                    string name = QueryValue(query, "name");
                    if (name.Length == 0) name = "file";
                    string id = QueryValue(query, "id");
                    if (!IdRx.IsMatch(id)) { Reply(ns, 400, "Bad id"); return; }

                    long total = headers.TryGetValue("X-Total", out var tt) && long.TryParse(tt, out var tv) ? tv : 0;
                    long offset = headers.TryGetValue("X-Offset", out var oo) && long.TryParse(oo, out var ov) ? ov : 0;

                    string part = PartPath(id);
                    long haveBytes = File.Exists(part) ? new FileInfo(part).Length : 0;
                    if (offset > 0 && offset != haveBytes) { Reply(ns, 409, haveBytes.ToString()); return; }

                    started = true;
                    begin("Receiving", name);

                    long done = 0;
                    long denom = total > 0 ? total : offset + length;
                    var sw = Stopwatch.StartNew();
                    long lastReport = 0;
                    using (var fs = new FileStream(part, offset == 0 ? FileMode.Create : FileMode.Append,
                               FileAccess.Write, FileShare.None, 1 << 20, FileOptions.SequentialScan))
                    {
                        if (leftover.Length > 0) { fs.Write(leftover, 0, leftover.Length); done += leftover.Length; }
                        var buf = new byte[256 * 1024];
                        while (done < length)
                        {
                            int n = ns.Read(buf, 0, (int)Math.Min(buf.Length, length - done));
                            if (n <= 0) throw new IOException("connection closed");
                            fs.Write(buf, 0, n);
                            done += n;
                            if (sw.ElapsedMilliseconds - lastReport > 100 && denom > 0)
                            {
                                lastReport = sw.ElapsedMilliseconds;
                                progress((double)(offset + done) / denom);
                            }
                        }
                    }

                    if (total > 0 && offset + done != total) throw new IOException("incomplete");

                    string finalPath = UniquePath(name);
                    File.Move(part, finalPath);
                    completed = true;
                    finish(true, Path.GetFileName(finalPath), finalPath);
                    Reply(ns, 200, "OK");
                    return;
                }

                Reply(ns, 404, "Not found");
            }
        }
        catch
        {
            // keep the .part file: the phone will reconnect and carry on from where it stopped
            if (started && !completed) finish(false, "Waiting for your phone...", null);
        }
    }

    static string Preview(string t)
    {
        t = t.Replace("\r", " ").Replace("\n", " ").Trim();
        return t.Length > 60 ? t[..60] + "..." : t;
    }

    static string UniquePath(string name)
    {
        name = Path.GetFileName(name);
        foreach (var ch in Path.GetInvalidFileNameChars()) name = name.Replace(ch, '_');
        if (string.IsNullOrWhiteSpace(name)) name = "file";
        var p = Path.Combine(Config.SaveDir, name);
        if (!File.Exists(p)) return p;
        var stem = Path.GetFileNameWithoutExtension(name);
        var ext = Path.GetExtension(name);
        for (int i = 1; ; i++)
        {
            p = Path.Combine(Config.SaveDir, $"{stem} ({i}){ext}");
            if (!File.Exists(p)) return p;
        }
    }
}

// ---------------------------------------------------------------- overlay card (small, thin progress line)

sealed class Overlay : Form
{
    enum State { Receiving, Done, Paused }

    static Overlay? cur;

    string title = "", sub = "";
    double frac;
    State state = State.Receiving;
    bool closing;
    string? openPath;

    readonly System.Windows.Forms.Timer anim = new() { Interval = 8 };
    readonly System.Windows.Forms.Timer hold = new() { Interval = 2600 };
    readonly System.Diagnostics.Stopwatch sw = new();
    bool slidingIn = true;
    int startY, endY;

    const int W = 320, H = 60;

    // ---- static entry points (UI thread)

    public static void Begin(string t, string s)
    {
        if (cur == null || cur.IsDisposed || cur.closing)
        {
            cur = new Overlay();
            cur.Show();
            cur.SlideIn();
        }
        cur.hold.Stop();
        cur.title = t; cur.sub = s; cur.frac = 0; cur.state = State.Receiving; cur.openPath = null;
        cur.Invalidate();
    }

    public static void Progress(double f)
    {
        if (cur == null || cur.IsDisposed) return;
        cur.frac = Math.Clamp(f, 0, 1);
        cur.Invalidate();
    }

    public static void Finish(bool ok, string s, string? path)
    {
        if (cur == null || cur.IsDisposed) return;
        cur.state = ok ? State.Done : State.Paused;
        cur.title = ok ? (cur.title == "Text copied" ? "Text copied" : "Received") : "Paused";
        cur.sub = s;
        cur.openPath = path;
        if (ok) cur.frac = 1;
        cur.hold.Interval = ok ? 2600 : 15000;
        cur.Invalidate();
        cur.hold.Stop(); cur.hold.Start();
    }

    // ---- form

    Overlay()
    {
        FormBorderStyle = FormBorderStyle.None;
        ShowInTaskbar = false;
        TopMost = true;
        StartPosition = FormStartPosition.Manual;
        Size = new Size(W, H);
        BackColor = Color.FromArgb(28, 28, 33);
        DoubleBuffered = true;
        Opacity = 0;
        Cursor = Cursors.Hand;

        var wa = Screen.PrimaryScreen!.WorkingArea;
        endY = wa.Top + 14;
        startY = wa.Top - H - 4;
        Location = new Point(wa.Left + (wa.Width - W) / 2, startY);

        using var gp = RoundedRect(new Rectangle(0, 0, W, H), 18);
        Region = new Region(gp);

        anim.Tick += (_, _) => Tick();
        hold.Tick += (_, _) => { hold.Stop(); SlideOut(); };
        Click += (_, _) =>
        {
            if (openPath != null)
                Process.Start(new ProcessStartInfo("explorer.exe", "/select,\"" + openPath + "\"") { UseShellExecute = true });
            SlideOut();
        };
    }

    protected override bool ShowWithoutActivation => true;

    protected override CreateParams CreateParams
    {
        get
        {
            var cp = base.CreateParams;
            cp.ExStyle |= 0x08000000 | 0x00000080; // WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW
            return cp;
        }
    }

    void SlideIn() { slidingIn = true; sw.Restart(); anim.Start(); }

    void SlideOut()
    {
        if (closing) return;
        closing = true; slidingIn = false; sw.Restart(); anim.Start();
    }

    void Tick()
    {
        double t = sw.Elapsed.TotalSeconds;
        if (slidingIn)
        {
            // damped spring: overshoots slightly then settles
            double s = 1 - Math.Exp(-7.0 * t) * Math.Cos(12.0 * t);
            Top = (int)(startY + (endY - startY) * s);
            Opacity = Math.Min(1.0, t / 0.10);
            if (t > 0.9) { Top = endY; Opacity = 1; anim.Stop(); }
        }
        else
        {
            double u = Math.Min(1.0, t / 0.28);
            double s = u * u;
            Top = (int)(endY + (startY - endY) * s);
            Opacity = 1 - u;
            if (u >= 1) { anim.Stop(); hold.Stop(); Close(); }
        }
    }

    // ---- painting

    static GraphicsPath RoundedRect(Rectangle r, int radius)
    {
        int d = radius * 2;
        var p = new GraphicsPath();
        p.AddArc(r.X, r.Y, d, d, 180, 90);
        p.AddArc(r.Right - d, r.Y, d, d, 270, 90);
        p.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90);
        p.AddArc(r.X, r.Bottom - d, d, d, 90, 90);
        p.CloseFigure();
        return p;
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics;
        g.SmoothingMode = SmoothingMode.AntiAlias;
        g.Clear(BackColor);

        var accent = state == State.Done ? Color.FromArgb(52, 199, 120)
                   : state == State.Paused ? Color.FromArgb(245, 166, 35)
                   : Color.FromArgb(94, 160, 255);

        using (var br = new SolidBrush(accent))
            g.FillEllipse(br, 14, 14, 32, 32);

        using (var pen = new Pen(Color.White, 2.6f) { StartCap = LineCap.Round, EndCap = LineCap.Round, LineJoin = LineJoin.Round })
        {
            if (state == State.Done)
                g.DrawLines(pen, new[] { new Point(22, 30), new Point(28, 36), new Point(38, 24) });
            else if (state == State.Paused)
            {
                g.DrawLine(pen, 25, 23, 25, 37);
                g.DrawLine(pen, 35, 23, 35, 37);
            }
            else
            {
                g.DrawLine(pen, 30, 22, 30, 37);
                g.DrawLine(pen, 24, 31, 30, 37);
                g.DrawLine(pen, 36, 31, 30, 37);
            }
        }

        using var f1 = new Font("Segoe UI Semibold", 10.5f);
        using var f2 = new Font("Segoe UI", 9f);
        var flags = TextFormatFlags.Left | TextFormatFlags.VerticalCenter | TextFormatFlags.EndEllipsis | TextFormatFlags.NoPadding;
        TextRenderer.DrawText(g, title, f1, new Rectangle(58, 8, W - 58 - 56, 20), Color.White, flags);
        TextRenderer.DrawText(g, sub, f2, new Rectangle(58, 28, W - 58 - 16, 16), Color.FromArgb(165, 165, 178), flags);

        if (state == State.Receiving)
        {
            if (frac > 0)
                TextRenderer.DrawText(g, (int)(frac * 100) + "%", f2, new Rectangle(W - 56, 8, 42, 20),
                    Color.FromArgb(165, 165, 178), TextFormatFlags.Right | TextFormatFlags.VerticalCenter | TextFormatFlags.NoPadding);

            // thin progress line
            int x = 58, w = W - 58 - 16, y = H - 12;
            using (var track = new SolidBrush(Color.FromArgb(60, 60, 70)))
                g.FillRectangle(track, x, y, w, 3);
            using (var fill = new SolidBrush(accent))
                g.FillRectangle(fill, x, y, (float)(w * frac), 3);
        }
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing) { anim.Dispose(); hold.Dispose(); }
        base.Dispose(disposing);
    }
}
