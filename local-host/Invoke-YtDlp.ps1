param(
    [Parameter(Mandatory = $true)]
    [string]$Url,

    [Parameter(Mandatory = $true)]
    [string]$YtDlpPath,

    [string]$FfmpegPath = '',

    [string]$DenoPath = '',

    [string]$VideoQuality = 'best',

    [string]$ThumbnailFormat = 'off',

    [Parameter(Mandatory = $true)]
    [string]$OutputTemplate,

    [string]$MergeAudioVideo = 'true',

    [string]$MergeOutputFormat = 'mp4',

    [int]$FragmentConcurrency = 4,

    [string]$BandwidthLimit = '',

    [int]$PlaylistEnd = 0,

    [int]$RetryLimit = 5,

    [int]$RetryBackoffSeconds = 2,

    [int]$SocketTimeoutSeconds = 30,

    [string]$ExistingFilePolicy = 'skip',

    [string]$AllowImageDownloads = 'false',

    [string]$ForceDownload = 'false',

    [Parameter(Mandatory = $true)]
    [string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'

# Ép toàn bộ đường ống dùng UTF-8 để tên file tiếng Việt/Hàn/emoji không bị hỏng khi Node đọc lại.
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[Console]::OutputEncoding = $utf8NoBom
[Console]::InputEncoding = $utf8NoBom
$OutputEncoding = $utf8NoBom
$env:PYTHONUTF8 = '1'
$env:PYTHONIOENCODING = 'utf-8'

function ConvertTo-StrictBoolean {
    param(
        [Parameter(Mandatory = $true)][string]$Value,
        [Parameter(Mandatory = $true)][string]$Name
    )
    $normalized = $Value.Trim().ToLowerInvariant()
    if (@('true', '1', 'yes', 'on') -contains $normalized) { return $true }
    if (@('false', '0', 'no', 'off') -contains $normalized) { return $false }
    throw "$Name must be true/false, yes/no, on/off, or 1/0."
}

try {
    $mergeAudioVideoEnabled = ConvertTo-StrictBoolean -Value $MergeAudioVideo -Name 'MergeAudioVideo'
    $allowImageDownloadsEnabled = ConvertTo-StrictBoolean -Value $AllowImageDownloads -Name 'AllowImageDownloads'
    $forceDownloadEnabled = ConvertTo-StrictBoolean -Value $ForceDownload -Name 'ForceDownload'

    $parsedUrl = [Uri]$Url
    if ($parsedUrl.Scheme -notin @('http', 'https')) {
        throw 'Only HTTP and HTTPS URLs are supported.'
    }

    New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null

    $resolvedUrl = $Url
    if ($parsedUrl.Host -match '(^|\.)tiktok\.com$' -and $parsedUrl.Host -match '^([vm]|vt)\.tiktok\.com$') {
        try {
            $redirect = Invoke-WebRequest -Uri $Url -Method Head -MaximumRedirection 10 -UseBasicParsing -TimeoutSec $SocketTimeoutSeconds
            if ($redirect.BaseResponse.RequestUri) {
                $resolvedUrl = $redirect.BaseResponse.RequestUri.AbsoluteUri
            } elseif ($redirect.BaseResponse.ResponseUri) {
                $resolvedUrl = $redirect.BaseResponse.ResponseUri.AbsoluteUri
            }
        } catch {
            try {
                $redirect = Invoke-WebRequest -Uri $Url -MaximumRedirection 10 -UseBasicParsing -TimeoutSec $SocketTimeoutSeconds
                if ($redirect.BaseResponse.ResponseUri) {
                    $resolvedUrl = $redirect.BaseResponse.ResponseUri.AbsoluteUri
                } elseif ($redirect.BaseResponse.RequestUri) {
                    $resolvedUrl = $redirect.BaseResponse.RequestUri.AbsoluteUri
                }
            } catch {
                # Keep the short URL if the redirect request fails; yt-dlp/gallery-dl
                # may still resolve it on their own.
            }
        }
    }

    $resolvedUri = [Uri]$resolvedUrl
    $isDirectImage = $parsedUrl.AbsolutePath -match '\.(avif|bmp|gif|jpe?g|png|webp|tiff?)$'
    if ($isDirectImage) {
        if (-not $allowImageDownloadsEnabled) { throw 'Image URL downloads are disabled in Download settings.' }
        $extension = [IO.Path]::GetExtension($parsedUrl.AbsolutePath).ToLowerInvariant()
        if ($extension -notin @('.avif','.bmp','.gif','.jpeg','.jpg','.png','.tif','.tiff','.webp')) { throw 'Unsupported direct image format.' }
        $name = [IO.Path]::GetFileNameWithoutExtension($parsedUrl.AbsolutePath) -replace '[<>:"/\\|?*]', '_'
        if ([string]::IsNullOrWhiteSpace($name)) { $name = 'image_' + [DateTime]::UtcNow.ToString('yyyyMMdd_HHmmss') }
        $target = Join-Path $OutputDirectory ($name + $extension)
        if ((Test-Path -LiteralPath $target) -and -not $forceDownloadEnabled -and $ExistingFilePolicy -eq 'skip') {
            [Console]::Out.WriteLine("__OUTPUT__$target")
            exit 0
        }
        if ((Test-Path -LiteralPath $target) -and $ExistingFilePolicy -eq 'rename' -and -not $forceDownloadEnabled) { $target = Join-Path $OutputDirectory ($name + '_' + [DateTime]::UtcNow.ToString('yyyyMMdd_HHmmss') + $extension) }
        if ($forceDownloadEnabled -or $ExistingFilePolicy -eq 'overwrite') { Remove-Item -LiteralPath $target -Force -ErrorAction SilentlyContinue }
        Invoke-WebRequest -Uri $Url -OutFile $target -UseBasicParsing -TimeoutSec $SocketTimeoutSeconds
        if (-not (Test-Path -LiteralPath $target) -or (Get-Item -LiteralPath $target).Length -eq 0) { throw 'Image download returned an empty file.' }
        [Console]::Out.WriteLine("__OUTPUT__$target")
        exit 0
    }

    $resolvedUri = [Uri]$resolvedUrl
    $isTikTokPhoto = $resolvedUri.Host -match '(^|\.)tiktok\.com$' -and $resolvedUri.AbsolutePath -match '/photo/\d+'
    if ($isTikTokPhoto) {
        $galleryArgs = @('-m', 'gallery_dl')
        $pythonRoots = @(
            'C:\Users\neopr\AppData\Local\Python\pythoncore-3.14-64\python.exe',
            'C:\Users\neopr\AppData\Local\Python\bin\python.exe',
            "$env:LOCALAPPDATA\Python\pythoncore-3.14-64\python.exe",
            "$env:LOCALAPPDATA\Python\bin\python.exe"
        )
        $galleryLauncher = $null
        foreach ($pythonRoot in $pythonRoots) {
            if ($pythonRoot -and (Test-Path -LiteralPath $pythonRoot)) {
                $galleryLauncher = Get-Item -LiteralPath $pythonRoot
                break
            }
        }
        if (-not $galleryLauncher) {
            $launcher = Get-Command 'py.exe' -ErrorAction SilentlyContinue
            if ($launcher) { $galleryLauncher = $launcher }
        }
        if (-not $galleryLauncher) {
            throw 'gallery-dl fallback requires the user Python installation or py.exe.'
        }
        $galleryCommand = if ($galleryLauncher.PSObject.Properties['Source']) { $galleryLauncher.Source } else { $galleryLauncher.FullName }
        if (-not $galleryCommand) { $galleryCommand = $galleryLauncher.Path }
        $galleryOutput = & $galleryCommand @galleryArgs '--directory' $OutputDirectory '--Print' 'after:__OUTPUT__{_path}' $resolvedUrl 2>&1
        $galleryExitCode = $LASTEXITCODE
        foreach ($galleryLine in $galleryOutput) { [Console]::Out.WriteLine([string]$galleryLine) }
        if ($galleryExitCode -ne 0) { exit $galleryExitCode }
        [Console]::Out.WriteLine('TikTok photo images downloaded by gallery-dl.')
        exit 0
    }

    $ytDlpArguments = @(
        '--newline', '--no-warnings', '--progress',
        '-o', $OutputTemplate,
        '--print', 'after_move:__OUTPUT__%(filepath)s'
    )
    $formatSelector = switch ($VideoQuality) {
        '1080p' { if ($mergeAudioVideoEnabled) { 'bv*[height<=1080]+ba/b[height<=1080]' } else { 'best[height<=1080]/best' } }
        '720p' { if ($mergeAudioVideoEnabled) { 'bv*[height<=720]+ba/b[height<=720]' } else { 'best[height<=720]/best' } }
        'audio' { 'bestaudio/best' }
        default { if ($mergeAudioVideoEnabled) { 'bv*+ba/b' } else { 'best' } }
    }
    $ytDlpArguments += @('--format', $formatSelector)
    if ($VideoQuality -eq 'audio') {
        $ytDlpArguments += @('--extract-audio', '--audio-format', 'mp3')
    } elseif ($mergeAudioVideoEnabled -and $MergeOutputFormat -in @('mp4', 'flv', 'avi', 'mkv', 'ts', 'webm')) {
        $ytDlpArguments += @('--remux-video', $MergeOutputFormat)
    }
    if ($ThumbnailFormat -ne 'off') {
        $ytDlpArguments += '--write-thumbnail'
        if ($ThumbnailFormat -in @('jpg', 'png', 'webp')) {
            $ytDlpArguments += @('--convert-thumbnails', $ThumbnailFormat)
        }
    }
    $ytDlpArguments += @(
        '--concurrent-fragments', [string]$FragmentConcurrency,
        '--retries', [string]$RetryLimit,
        '--fragment-retries', [string]$RetryLimit,
        '--file-access-retries', [string]$RetryLimit,
        '--socket-timeout', [string]$SocketTimeoutSeconds,
        '--retry-sleep', "http:exp=$RetryBackoffSeconds`:60",
        '--retry-sleep', "fragment:exp=$RetryBackoffSeconds`:60"
    )
    if (-not [string]::IsNullOrWhiteSpace($BandwidthLimit)) {
        $ytDlpArguments += @('--limit-rate', $BandwidthLimit)
    }
    if ($PlaylistEnd -gt 0) {
        # Link profile/kênh/playlist: chỉ lấy N video mới nhất, tránh tải cả kênh.
        $ytDlpArguments += @('--playlist-end', [string]$PlaylistEnd)
    }
    switch ($ExistingFilePolicy) {
        'overwrite' { $ytDlpArguments += '--force-overwrites' }
        default { $ytDlpArguments += '--no-overwrites' }
    }
    if ($forceDownloadEnabled) { $ytDlpArguments += '--force-overwrites' }
    if (-not [string]::IsNullOrWhiteSpace($FfmpegPath)) {
        $ytDlpArguments += @('--ffmpeg-location', $FfmpegPath)
    }
    if (-not [string]::IsNullOrWhiteSpace($DenoPath)) {
        $ytDlpArguments += @('--js-runtimes', "deno:$DenoPath")
    }
    $ytDlpArguments += $resolvedUrl

    & $YtDlpPath @ytDlpArguments 2>&1 | ForEach-Object {
            [Console]::Out.WriteLine([string]$_)
        }

    $exitCode = $LASTEXITCODE
    if ($null -eq $exitCode) {
        $exitCode = 1
    }
    exit $exitCode
}
catch {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 2
}
