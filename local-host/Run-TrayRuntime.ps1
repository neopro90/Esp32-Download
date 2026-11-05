$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

$script:runtimeRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$script:dataRoot = Join-Path $script:runtimeRoot 'data'
$script:stdoutLog = Join-Path $script:dataRoot 'runtime.stdout.log'
$script:stderrLog = Join-Path $script:dataRoot 'runtime.stderr.log'
$script:nodeProcess = $null
$script:manualStop = $false
$script:notifyIcon = $null

New-Item -ItemType Directory -Path $script:dataRoot -Force | Out-Null

function Test-RuntimeListening {
    try {
        $tcp = New-Object System.Net.Sockets.TcpClient
        $iar = $tcp.BeginConnect('127.0.0.1', 8787, $null, $null)
        $wait = $iar.AsyncWaitHandle.WaitOne(300, $false)
        if ($wait -and $tcp.Connected) {
            $tcp.EndConnect($iar)
            $tcp.Close()
            return $true
        }
        $tcp.Close()
        return $false
    } catch {
        return $false
    }
}

function Set-RuntimeIconState {
    if (-not $script:notifyIcon) { return }
    $listening = Test-RuntimeListening
    if (($script:nodeProcess -and -not $script:nodeProcess.HasExited) -or $listening) {
        $script:notifyIcon.Text = 'ESP Download · runtime đang chạy'
    }
    else {
        $script:notifyIcon.Text = 'ESP Download · runtime đã dừng'
    }
}

function Start-LocalRuntime {
    $script:manualStop = $false
    if (Test-RuntimeListening) {
        Set-RuntimeIconState
        return
    }
    if ($script:nodeProcess -and -not $script:nodeProcess.HasExited) { return }
    try {
        $nodePath = (Get-Command node.exe -ErrorAction Stop).Source
        # Giữ log lần chạy trước (.old) để có thể debug sau khi runtime bị crash.
        if (Test-Path -LiteralPath $script:stdoutLog) { Move-Item -LiteralPath $script:stdoutLog -Destination ($script:stdoutLog + '.old') -Force }
        if (Test-Path -LiteralPath $script:stderrLog) { Move-Item -LiteralPath $script:stderrLog -Destination ($script:stderrLog + '.old') -Force }
        $script:nodeProcess = Start-Process -FilePath $nodePath -ArgumentList 'server.mjs' `
            -WorkingDirectory $script:runtimeRoot -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput $script:stdoutLog -RedirectStandardError $script:stderrLog
        Start-Sleep -Milliseconds 500
        Set-RuntimeIconState
    }
    catch {
        $script:notifyIcon.BalloonTipTitle = 'ESP Download'
        $script:notifyIcon.BalloonTipText = "Không khởi động được runtime: $($_.Exception.Message)"
        $script:notifyIcon.ShowBalloonTip(5000)
    }
}

function Stop-LocalRuntime {
    $script:manualStop = $true
    if ($script:nodeProcess -and -not $script:nodeProcess.HasExited) {
        $taskkill = Join-Path $env:SystemRoot 'System32\taskkill.exe'
        Start-Process -FilePath $taskkill -ArgumentList @('/PID', [string]$script:nodeProcess.Id, '/T', '/F') `
            -WindowStyle Hidden -Wait | Out-Null
        $script:nodeProcess = $null
    }
    try {
        Stop-ScheduledTask -TaskName 'Esp32DownloadRuntime' -ErrorAction SilentlyContinue
    } catch {}
    Set-RuntimeIconState
}

$menu = New-Object System.Windows.Forms.ContextMenuStrip
$openItem = $menu.Items.Add('Mở dashboard local')
$openItem.Add_Click({ Start-Process 'http://127.0.0.1:8787' })
$telegramLogItem = $menu.Items.Add('Mở cửa sổ log URL Telegram')
$telegramLogItem.Add_Click({ Start-Process 'http://127.0.0.1:8787/#telegram-log-panel' })
$startItem = $menu.Items.Add('Khởi động runtime')
$startItem.Add_Click({ Start-LocalRuntime })
$stopItem = $menu.Items.Add('Dừng runtime')
$stopItem.Add_Click({ Stop-LocalRuntime })
$logItem = $menu.Items.Add('Mở log runtime')
$logItem.Add_Click({
    if (Test-Path -LiteralPath $script:stderrLog) { Start-Process notepad.exe -ArgumentList ('"{0}"' -f $script:stderrLog) }
    elseif (Test-Path -LiteralPath $script:stdoutLog) { Start-Process notepad.exe -ArgumentList ('"{0}"' -f $script:stdoutLog) }
})
$menu.Items.Add((New-Object System.Windows.Forms.ToolStripSeparator)) | Out-Null
$exitItem = $menu.Items.Add('Thoát')
$exitItem.Add_Click({
    Stop-LocalRuntime
    $script:notifyIcon.Visible = $false
    $script:notifyIcon.Dispose()
    [System.Windows.Forms.Application]::Exit()
})

$script:notifyIcon = New-Object System.Windows.Forms.NotifyIcon
$script:notifyIcon.Icon = [System.Drawing.SystemIcons]::Application
$script:notifyIcon.ContextMenuStrip = $menu
$script:notifyIcon.Visible = $true
$script:notifyIcon.Text = 'ESP Download · đang khởi động'
$script:notifyIcon.Add_DoubleClick({ Start-Process 'http://127.0.0.1:8787' })

$timer = New-Object System.Windows.Forms.Timer
$timer.Interval = 2500
$timer.Add_Tick({
    if ($script:nodeProcess -and $script:nodeProcess.HasExited) { $script:nodeProcess = $null }
    $listening = Test-RuntimeListening
    if (-not $script:manualStop -and -not $script:nodeProcess -and -not $listening) { Start-LocalRuntime }
    Set-RuntimeIconState
})
$timer.Start()

$form = New-Object System.Windows.Forms.Form
$form.ShowInTaskbar = $false
$form.ShowIcon = $false
$form.WindowState = [System.Windows.Forms.FormWindowState]::Minimized
$form.FormBorderStyle = [System.Windows.Forms.FormBorderStyle]::FixedToolWindow
$form.Add_Shown({ $this.Hide() })

Start-LocalRuntime
[System.Windows.Forms.Application]::Run($form)
