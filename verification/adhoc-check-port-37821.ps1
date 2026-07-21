$j = Get-CimInstance Win32_Process -Filter "Name='javaw.exe'"
if ($j) { $j | Select-Object ProcessId,CommandLine | Format-List } else { Write-Output 'NO_JAVAW' }
$p = Get-NetTCPConnection -LocalPort 37821 -ErrorAction SilentlyContinue
if ($p) { $p | Select-Object LocalPort,State,OwningProcess } else { Write-Output 'PORT_37821_FREE' }
