param(
    [ValidateSet('download','upload-owned-playlist','cleanup-owned-playlist')][string]$Phase='download',
    [string]$Serial='emulator-5556',
    [string]$ExpectedPlaylistName,
    [string]$SessionPath='C:\Users\adars\AppData\Local\EchoMusic\verification\android-session.json'
)
$ErrorActionPreference='Stop'
if($Serial -notmatch '^emulator-[0-9]+$'){throw 'Live fixture checks are restricted to an emulator'}
$adb='C:\Users\adars\AppData\Local\Android\Sdk\platform-tools\adb.exe'
if(!(Test-Path -LiteralPath $SessionPath)){throw 'Prepare the private short-lived session first'}
& $adb -s $Serial shell run-as dev.echo.data.test mkdir -p cache
if($LASTEXITCODE -ne 0){throw 'Test app private cache is unavailable'}
$start=[System.Diagnostics.ProcessStartInfo]::new()
$start.FileName=$adb
$start.UseShellExecute=$false
$start.RedirectStandardInput=$true
$start.RedirectStandardOutput=$true
$start.RedirectStandardError=$true
@('-s',$Serial,'shell',"run-as dev.echo.data.test sh -c 'cat > cache/echo-firebase-session.json'") | ForEach-Object { $start.ArgumentList.Add($_) }
$transfer=[System.Diagnostics.Process]::Start($start)
$inputFile=[System.IO.File]::OpenRead($SessionPath)
try { $inputFile.CopyTo($transfer.StandardInput.BaseStream) }
finally { $inputFile.Dispose(); $transfer.StandardInput.Close() }
$transfer.WaitForExit()
if($transfer.ExitCode -ne 0){throw 'Private session handoff failed'}
$arguments=@('-s',$Serial,'shell','am','instrument','-w','-e','class','dev.echo.data.library.FirebaseCloudRoundtripTest',
    '-e','echoFirebaseLive','true','-e','echoFirebasePhase',$Phase)
if($Phase -ne 'download'){$arguments+=@('-e','echoFirebaseWriteOwnedPlaylist','true')}
if($ExpectedPlaylistName){$arguments+=@('-e','echoFirebaseExpectedPlaylistName',"'$ExpectedPlaylistName'")}
$arguments+='dev.echo.data.test/androidx.test.runner.AndroidJUnitRunner'
try {
    $result=& $adb @arguments
    $result
    if($LASTEXITCODE -ne 0 -or ($result -join "`n") -notmatch 'OK \(1 test\)'){throw 'Android Firebase roundtrip check failed'}
} finally {
    & $adb -s $Serial shell run-as dev.echo.data.test rm -f cache/echo-firebase-session.json
}
