[CmdletBinding()]
param([int]$Port=37836,[string]$JavaHome='',[string]$EvidenceDirectory='')
$ErrorActionPreference='Stop'; Add-Type -AssemblyName System.Net.Http
$root=(Resolve-Path (Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) '..')).Path
if(!$JavaHome){$JavaHome=Join-Path $env:USERPROFILE 'scoop\apps\temurin21-jdk\current'}
if(!$EvidenceDirectory){$EvidenceDirectory=Join-Path $root 'verification\evidence'}
New-Item -ItemType Directory -Force -Path $EvidenceDirectory|Out-Null
$run=[DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')+'-'+([guid]::NewGuid().ToString('N').Substring(0,8))
$reportPath=Join-Path $EvidenceDirectory "primitive-survival-house-axe-$run.json"; $evidencePath=Join-Path $EvidenceDirectory "primitive-survival-house-axe-$run.jsonl"
$stdoutPath=Join-Path $EvidenceDirectory "primitive-survival-house-axe-$run.minecraft.stdout.log"; $stderrPath=Join-Path $EvidenceDirectory "primitive-survival-house-axe-$run.minecraft.stderr.log"
$token=[guid]::NewGuid().ToString('N'); $uri="http://127.0.0.1:$Port/mcp"; $session=$null; $id=0; $http=[System.Net.Http.HttpClient]::new();$http.Timeout=[TimeSpan]::FromMinutes(10);$launcher=$null
$r=[ordered]@{status='RUNNING'; startedAtUtc=[DateTime]::UtcNow.ToString('o'); minecraftVersion='1.21.1';loader='neoforge';keepFocus=$true;port=$Port;forbiddenCalls=@();calls=0;predicates=[ordered]@{};navigation=[ordered]@{calls=0;plannedPathLength=0;pathNodesVisited=0;replans=0;safetyInterventions=@();attempts=@()};errors=@()}
function Log($x){
  $x.timestampUtc=[DateTime]::UtcNow.ToString('o');$line=$x|ConvertTo-Json -Depth 50 -Compress
  $bytes=[Text.Encoding]::UTF8.GetBytes($line+[Environment]::NewLine);$deadline=[DateTime]::UtcNow.AddSeconds(30)
  do { try {
    # The evidence directory is sync-backed. Open with sharing enabled and tolerate a short scanner/sync lock.
    $stream=[System.IO.File]::Open($evidencePath,[System.IO.FileMode]::Append,[System.IO.FileAccess]::Write,[System.IO.FileShare]::ReadWrite)
    try {$stream.Write($bytes,0,$bytes.Length);$stream.Flush($true)} finally {$stream.Dispose()};return
  } catch [System.IO.IOException] { Start-Sleep -Milliseconds 250 } } while([DateTime]::UtcNow -lt $deadline)
  throw "evidence JSONL remained locked for 30 seconds: $evidencePath"
}
function Rpc([string]$m,[hashtable]$p=@{}){$script:id++;$b=@{jsonrpc='2.0';id=$script:id;method=$m;params=$p}|ConvertTo-Json -Depth 50 -Compress;$q=[System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post,$uri);$q.Headers.Add('X-Lodestone-Token',$token);if($session){$q.Headers.Add('Mcp-Session-Id',$session);$q.Headers.Add('MCP-Protocol-Version','2025-11-25')};$q.Content=[System.Net.Http.StringContent]::new($b,[Text.Encoding]::UTF8,'application/json');$z=$http.SendAsync($q).Result;if($z.Headers.Contains('Mcp-Session-Id')){$script:session=$z.Headers.GetValues('Mcp-Session-Id')-join ''};[pscustomobject]@{http=[int]$z.StatusCode;json=($z.Content.ReadAsStringAsync().Result|ConvertFrom-Json)}}
function Call([string]$name,[hashtable]$inputArgs=@{}){$requested=if($name -eq 'lodestone_capability_invoke'){[string]$inputArgs.capability}else{$name};$forbiddenGoal=($requested -match '^minecraft\.goal\.' -and $requested -ne 'minecraft.goal.navigation.safe-waypoint');if($requested -match '^minecraft_goal' -or $forbiddenGoal -or $requested -match '^minecraft\.command\.' -or $requested -eq 'minecraft.world.blocks.write'){throw "FORBIDDEN_CALL:$requested"};$x=Rpc 'tools/call' @{name=$name;arguments=$inputArgs};$p=$x.json.result.structuredContent;if($x.json.error){$out=[pscustomobject]@{status='rpc-error';error=$x.json.error;output=$null}}elseif(!$p){$out=[pscustomobject]@{status='empty';error=$null;output=$null}}elseif($p.status){$out=[pscustomobject]@{status=[string]$p.status;error=$p.error;output=$p.output}}else{$out=[pscustomobject]@{status='ok';error=$null;output=$p}};$r.calls++;Log @{kind='tool';name=$name;input=$inputArgs;httpStatus=$x.http;status=$out.status;output=$out.output;error=$out.error};return $out}
function Must($x,[string]$label){if($x.status -ne 'ok'){throw "$label failed: $($x.status) $($x.error|ConvertTo-Json -Compress -Depth 8)"};return $x.output}
function Cap([string]$cap,[hashtable]$inputArgs=@{},[string]$version='1.0'){
  for($attempt=0;$attempt-lt 8;$attempt++){$result=Call 'lodestone_capability_invoke' @{capability=$cap;capabilityVersion=$version;input=$inputArgs;dryRun=$false};if($result.status -eq 'ok'){return $result.output};if([string]$result.error.code -eq 'CAPABILITY_QUARANTINED'){Must (Call 'lodestone_capability_invoke' @{capability='minecraft.session.reconcile';capabilityVersion='1.0';input=@{};dryRun=$false}) 'minecraft.session.reconcile'|Out-Null;Start-Sleep -Milliseconds 300;continue};if([string]$result.error.code -eq 'OUTCOME_INDETERMINATE' -and $cap -eq 'minecraft.goal.navigation.safe-waypoint'){
      # Do not replay a possibly committed navigation lease. Quiesce, re-observe on the next loop,
      # and force a fresh route decision from that observed state.
      Must (Call 'lodestone_capability_invoke' @{capability='minecraft.session.reconcile';capabilityVersion='1.0';input=@{};dryRun=$false}) 'minecraft.session.reconcile after indeterminate navigation'|Out-Null
      Log @{kind='replan';stage='navigation-outcome-indeterminate-reconciled';target=$inputArgs;attempt=$attempt}
      return [pscustomobject]@{reachedTarget=$false;plannedPathLength=0;pathNodesVisited=0;replans=1;safetyInterventions=@('outcome-indeterminate:reconciled');nearestReachablePoint=$null;commandsUsed=$false;directMutationUsed=$false;blocksMined=0;blocksPlaced=0}
    };if([string]$result.error.code -ne 'RATE_LIMIT_EXCEEDED'){return Must $result $cap};Start-Sleep -Milliseconds 450}
  throw "$cap remained rate limited after bounded retries"
}
function Batch([array]$a,[string]$label){$acts=@($a|%{[ordered]@{capability=$_.capability;capabilityVersion=$_.version;input=$_.input}});return Must (Call 'minecraft_subactions_execute' @{actions=$acts;maxDurationMs=600000;stopOnError=$true}) $label}
function Ui(){return Must (Call 'ui_state') 'ui_state'}
function WaitUiReady(){for($i=0;$i-lt 600;$i++){try{return Ui}catch{Start-Sleep -Milliseconds 500}};throw 'physical client UI bridge did not become ready'}
function WaitWorld(){for($i=0;$i-lt 240;$i++){try{$s=Ui;if($s.inWorld -and !$s.screenClass){return $s}}catch{};Start-Sleep -Milliseconds 500};throw 'world did not load'}
function WaitScreen([string]$rx){for($i=0;$i-lt 180;$i++){$s=Ui;if([string]$s.screenClass -match $rx){return $s};Start-Sleep -Milliseconds 500};throw "screen $rx did not appear"}
function Act([string]$c,[hashtable]$i=@{},[string]$v='1.0'){[pscustomobject]@{capability=$c;version=$v;input=$i}}
function Key([string]$k,[bool]$down){Cap 'minecraft.input.key.set' @{key=$k;down=$down}|Out-Null;if($down){Start-Sleep -Milliseconds 120}}
function LogUiTransition([string]$stage,[int]$attempt,[int]$poll,$s){Log @{kind='ui-transition';stage=$stage;attempt=$attempt;poll=$poll;screenClass=[string]$s.screenClass;open=[bool]$s.open;inWorld=[bool]$s.inWorld}}
function OpenInventoryBounded(){
  for($attempt=0;$attempt-lt 4;$attempt++){
    Cap 'minecraft.input.release-all' @{}|Out-Null
    $s=Ui;LogUiTransition 'inventory-preflight' $attempt -1 $s
    if([string]$s.screenClass -match 'AccessibilityOnboardingScreen|LoadingErrorScreen'){
      $dismiss=@($s.widgets|?{$_.active -and $_.visible -and $_.label -in @('Continue','Proceed to main menu')}|Select-Object -First 1)
      if($dismiss){Cap 'minecraft.ui.click' @{screenToken=[string]$s.screenToken;snapshotRevision=[string]$s.snapshotRevision;nodeId=[string]$dismiss[0].nodeId;button=0} '2.0'|Out-Null;Start-Sleep -Milliseconds 350;$s=Ui;LogUiTransition 'inventory-dismiss-onboarding' $attempt -1 $s}
    }
    if([string]$s.screenClass -match 'PauseScreen'){
      $back=@($s.widgets|?{$_.active -and $_.visible -and $_.label -eq 'Back to Game'}|Select-Object -First 1)
      if($back){Cap 'minecraft.ui.click' @{screenToken=[string]$s.screenToken;snapshotRevision=[string]$s.snapshotRevision;nodeId=[string]$back[0].nodeId;button=0} '2.0'|Out-Null}else{Cap 'minecraft.ui.key' @{key=256;scanCode=0;modifiers=0}|Out-Null}
      Start-Sleep -Milliseconds 350;$s=Ui;LogUiTransition 'inventory-close-pause' $attempt -1 $s
    }
    if($s.open -and [string]$s.screenClass -notmatch 'InventoryScreen'){Cap 'minecraft.ui.key' @{key=256;scanCode=0;modifiers=0}|Out-Null;Start-Sleep -Milliseconds 350;$s=Ui;LogUiTransition 'inventory-close-residual' $attempt -1 $s}
    if(!$s.inWorld -or $s.open -or [string]$s.screenClass){Start-Sleep -Milliseconds 250;continue}
    Cap 'minecraft.input.release-all' @{}|Out-Null
    # Mirror neoforge-keepfocus-flow-benchmark.ps1: native key-mapping dispatch for E, then observe InventoryScreen.
    Cap 'minecraft.ui.key' @{key=69;scanCode=0;modifiers=0}|Out-Null;Start-Sleep -Milliseconds 350
    for($poll=0;$poll-lt 40;$poll++){$s=Ui;LogUiTransition 'inventory-key69-poll' $attempt $poll $s;if($s.inWorld -and $s.open -and [string]$s.screenClass -match 'InventoryScreen'){return $s};Start-Sleep -Milliseconds 100}
    Cap 'minecraft.input.release-all' @{}|Out-Null;Cap 'minecraft.input.key.set' @{key='key.inventory';down=$false}|Out-Null;Start-Sleep -Milliseconds 80;Key 'key.inventory' $true;Key 'key.inventory' $false
    for($poll=0;$poll-lt 20;$poll++){$s=Ui;LogUiTransition 'inventory-held-key-fallback' $attempt $poll $s;if($s.inWorld -and $s.open -and [string]$s.screenClass -match 'InventoryScreen'){return $s};Start-Sleep -Milliseconds 100}
  };throw 'inventory screen did not open after four bounded primitive key retries'
}
function ReadContainer(){return Cap 'minecraft.inventory.container.read'}
function ClickFresh([int]$slot,[int]$button=0,[string]$clickType='PICKUP',[string]$label='container click'){
  $clicked=$false
  for($clickAttempt=0;$clickAttempt-lt 8;$clickAttempt++){
    $c=ReadContainer
    try{Cap 'minecraft.inventory.container.click' @{slot=$slot;revision=[int]$c.revision;button=$button;clickType=$clickType}|Out-Null;$clicked=$true;break}
    catch{if($_.Exception.Message -notmatch 'revision is stale'){throw};Log @{kind='replan';stage='container-revision-refresh';label=$label;slot=$slot;attempt=$clickAttempt;message=$_.Exception.Message};Start-Sleep -Milliseconds 150}
  }
  if(!$clicked){throw "$label could not obtain a current container revision after bounded retries"}
  Start-Sleep -Milliseconds 180
  return ReadContainer
}
function Inventory(){return Cap 'minecraft.inventory.read'}
function ItemCount([string]$item){$i=Inventory;return [int](($i.slots|?{$_.item -eq $item}|Measure-Object count -Sum).Sum)}
function ContainerItemSlot([string]$item,[int]$minimum=0){$c=ReadContainer;$s=@($c.slots|?{[int]$_.slot -ge $minimum -and $_.item -eq $item -and !$_.empty}|Select-Object -First 1);if(!$s){return -1};return [int]$s[0].slot}
function EnsureHotbar([string]$item){
  $i=Inventory;$h=@($i.slots|?{[int]$_.slot -ge 0 -and [int]$_.slot -le 8 -and $_.item -eq $item}|Select-Object -First 1)
  if($h){return [int]$h[0].slot}
  OpenInventoryBounded|Out-Null
  $slot=ContainerItemSlot $item 9;if($slot -lt 0){throw "$item is not present for hotbar transfer"}
  ClickFresh $slot 0 'QUICK_MOVE' "quick-move $item"|Out-Null
  Cap 'minecraft.ui.key' @{key=256;scanCode=0;modifiers=0}|Out-Null;WaitWorld|Out-Null
  $i=Inventory;$h=@($i.slots|?{[int]$_.slot -ge 0 -and [int]$_.slot -le 8 -and $_.item -eq $item}|Select-Object -First 1)
  if(!$h){throw "normal quick-move did not place $item in hotbar"};return [int]$h[0].slot
}
function RecordNavigation($nav,$requested,$candidate,[int]$attempt){
  $r.navigation.calls++;$r.navigation.plannedPathLength += [int]$nav.plannedPathLength;$r.navigation.pathNodesVisited += [int]$nav.pathNodesVisited;$r.navigation.replans += [int]$nav.replans
  $r.navigation.safetyInterventions += @($nav.safetyInterventions)
  $record=[ordered]@{attempt=$attempt;requested=$requested;candidate=$candidate;reachedTarget=[bool]$nav.reachedTarget;finalPosition=$nav.finalPosition;plannedPathLength=[int]$nav.plannedPathLength;pathNodesVisited=[int]$nav.pathNodesVisited;replans=[int]$nav.replans;safetyInterventions=@($nav.safetyInterventions);nearestReachablePoint=$nav.nearestReachablePoint}
  $r.navigation.attempts += $record;Log (@{kind='navigation-safe-waypoint'}+$record)
}
function AdvanceIntoUnloadedTerrain($target,[double]$desired){
  # The safe-waypoint planner intentionally has no route through unloaded chunks. Use only short,
  # observable input leases to load the next terrain band, then hand planning back to the planner.
  $moved=$false
  for($lease=0;$lease-lt 8;$lease++){
    $before=Cap 'minecraft.player.state.read';$safety=$before.worldObservation.player
    if([double]$before.health -le 6 -or [bool]$safety.inLava -or [bool]$safety.onFire -or [bool]$safety.inWater -or -not [bool]$safety.onGround){throw 'unsafe state while loading unexplored terrain'}
    $dx=[double]$target.x-[double]$before.position.x;$dz=[double]$target.z-[double]$before.position.z;$distance=[math]::Sqrt($dx*$dx+$dz*$dz)
    if($distance -le $desired){return $before}
    # Walking uses a level view. Mining and placement set a precise aim only immediately before
    # their action, so a completed mine cannot leave a downward camera pitch on the next movement lease.
    $yaw=[math]::Atan2(-$dx,$dz)*180/[math]::PI;Cap 'minecraft.player.look' @{yaw=$yaw;pitch=0}|Out-Null
    Cap 'minecraft.player.move' @{forward=1.0;strafe=0.0;jump=$true;sprint=$true;sneak=$false;durationMs=750} '2.0'|Out-Null;Start-Sleep -Milliseconds 850
    $after=Cap 'minecraft.player.state.read';$adx=[double]$target.x-[double]$after.position.x;$adz=[double]$target.z-[double]$after.position.z;$afterDistance=[math]::Sqrt($adx*$adx+$adz*$adz)
    Log @{kind='unloaded-terrain-step';lease=$lease;target=$target;before=$before.position;after=$after.position;beforeDistance=$distance;afterDistance=$afterDistance}
    if($afterDistance -ge $distance-.25){if($moved){return $after};throw 'raw movement did not make safe progress into unloaded terrain'}
    $moved=$true
  }
  return Cap 'minecraft.player.state.read'
}
function MoveNear($target,[double]$desired=3.0,[int]$attempts=18){
  $p=Cap 'minecraft.player.state.read';if([double]$p.health -le 6){throw 'health safety floor reached during movement'}
  $dx=[double]$target.x-[double]$p.position.x;$dz=[double]$target.z-[double]$p.position.z;$d=[math]::Sqrt($dx*$dx+$dz*$dz);if($d -le $desired){return $p}
  $tx=[int][math]::Round([double]$target.x);$tz=[int][math]::Round([double]$target.z);$ty=[int][math]::Round([double]$target.y);$radius=[int][math]::Max(1,[math]::Min(3,[math]::Ceiling($desired)))
  $columns=@();try{$hm=Cap 'minecraft.world.heightmap.read' @{x=$tx-$radius;z=$tz-$radius;sizeX=2*$radius+1;sizeZ=2*$radius+1;includeSurfaceBlocks=$false};$columns=@($hm.columns|?{$_.loaded -and !$_.empty})}catch{}
  $candidateRows=@();foreach($offset in @(@(0,0),@($radius,0),@(-$radius,0),@(0,$radius),@(0,-$radius),@(1,0),@(-1,0),@(0,1),@(0,-1),@($radius,$radius),@(-$radius,$radius),@($radius,-$radius),@(-$radius,-$radius))){$cx=$tx+[int]$offset[0];$cz=$tz+[int]$offset[1];$column=@($columns|?{[int]$_.x-eq$cx-and[int]$_.z-eq$cz}|Select-Object -First 1);$cy=if($column){[int]$column[0].height}else{$ty};$candidateRows+=[pscustomobject]@{x=$cx;y=$cy;z=$cz;fit=[math]::Abs([math]::Sqrt(($cx-$tx)*($cx-$tx)+($cz-$tz)*($cz-$tz))-$desired)}}
  $queue=[Collections.ArrayList]::new();$seen=[Collections.Generic.HashSet[string]]::new();$fallbackTarget=$target;foreach($c in @($candidateRows|Sort-Object fit)){if($seen.Add("$($c.x),$($c.y),$($c.z)")){[void]$queue.Add($c)}}
  $maxCalls=[int][math]::Min(6,[math]::Max(2,[math]::Ceiling($attempts/6)));$callIndex=0
  while($callIndex-lt$maxCalls -and $queue.Count){$candidate=$queue[0];$queue.RemoveAt(0)
    $nav=Cap 'minecraft.goal.navigation.safe-waypoint' @{targetX=[int]$candidate.x;targetY=[int]$candidate.y;targetZ=[int]$candidate.z;intelligence='high';safety='high';observation='loaded-chunks';combatPolicy='avoid';allowBlockBreaking=$false;allowBlockPlacing=$false;allowCommands=$false}
    RecordNavigation $nav $target $candidate $callIndex
    if([bool]$nav.commandsUsed -or [bool]$nav.directMutationUsed -or [int]$nav.blocksMined -ne 0 -or [int]$nav.blocksPlaced -ne 0){throw 'safe-waypoint violated no-command/no-mutation navigation policy'}
    if([bool]$nav.reachedTarget){$after=Cap 'minecraft.player.state.read';$adx=[double]$target.x-[double]$after.position.x;$adz=[double]$target.z-[double]$after.position.z;if([math]::Sqrt($adx*$adx+$adz*$adz)-le([math]::Max($desired,$radius+0.75))){return $after}}
    if($nav.nearestReachablePoint){$n=$nav.nearestReachablePoint;$fallbackTarget=[pscustomobject]@{x=[double]$target.x;y=[double]$n.y;z=[double]$target.z};$key="$([int]$n.x),$([int]$n.y),$([int]$n.z)";if($seen.Add($key)){[void]$queue.Insert(0,[pscustomobject]@{x=[int]$n.x;y=[int]$n.y;z=[int]$n.z;fit=0})}}
    $callIndex++;Start-Sleep -Milliseconds 1100
  }
  $fallback=AdvanceIntoUnloadedTerrain $fallbackTarget $desired
  Log @{kind='replan';stage='unloaded-terrain-fallback';target=$target;groundTarget=$fallbackTarget;plannerCalls=$callIndex;position=$fallback.position}
  return $fallback
}
function AimAtPoint($target,[double]$yOffset=0.5){
  $p=Cap 'minecraft.player.state.read';$dx=[double]$target.x-[double]$p.position.x;$dz=[double]$target.z-[double]$p.position.z;$dy=([double]$target.y+$yOffset)-([double]$p.position.y+1.62)
  $horizontal=[math]::Sqrt($dx*$dx+$dz*$dz);$yaw=[math]::Atan2(-$dx,$dz)*180/[math]::PI;$pitch=-[math]::Atan2($dy,[math]::Max(.01,$horizontal))*180/[math]::PI
  Cap 'minecraft.player.look' @{yaw=$yaw;pitch=[math]::Max(-89,[math]::Min(89,$pitch))}|Out-Null;Start-Sleep -Milliseconds 120
}
function MinePosition($target,[string]$fingerprint=''){
  MoveNear $target 3.4|Out-Null;$look=@{x=[int]$target.x;y=[int]$target.y;z=[int]$target.z};if($fingerprint){$look.blockFingerprint=$fingerprint}
  Cap 'minecraft.player.block.look-at' $look|Out-Null;Cap 'minecraft.player.block.mine' @{}|Out-Null;Start-Sleep -Milliseconds 350
}
function CollectNearbyDrops([string]$item,[string]$stage){
  # Item pickup is normal collision movement. Read the rendered item entities, then walk to each
  # observed position; do not alter inventory or entities directly.
  for($pass=0;$pass-lt 4;$pass++){
    Start-Sleep -Milliseconds 650
    $near=Cap 'minecraft.entity.nearby.read' @{radius=12;limit=64;includePlayers=$false;type='minecraft:item'}
    $drops=@($near.entities)
    Log @{kind='item-collection';stage=$stage;pass=$pass;item=$item;observedDrops=$drops.Count;beforeCount=(ItemCount $item)}
    foreach($drop in $drops){
      try{MoveNear ([pscustomobject]@{x=[double]$drop.position.x;y=[double]$drop.position.y;z=[double]$drop.position.z}) .3 12|Out-Null;Start-Sleep -Milliseconds 450}catch{Log @{kind='replan';stage='collect-drop';target=$drop.position;message=$_.Exception.Message}}
      if((ItemCount $item) -ge 3){return}
    }
    if((ItemCount $item) -ge 3){return}
  }
}
function FindReachableLog([string]$item){
  $p=Cap 'minecraft.player.state.read';$bx=[math]::Floor([double]$p.position.x);$by=[math]::Floor([double]$p.position.y);$bz=[math]::Floor([double]$p.position.z)
  $scan=Cap 'minecraft.world.blocks.read' @{x=$bx-5;y=$by-4;z=$bz-5;sizeX=11;sizeY=9;sizeZ=11};$reachable=@()
  foreach($cell in @($scan.blocks|?{$_.loaded -and $_.block -eq $item})){$dx=([double]$cell.position.x+.5)-[double]$p.position.x;$dy=([double]$cell.position.y+.5)-([double]$p.position.y+1.62);$dz=([double]$cell.position.z+.5)-[double]$p.position.z;$distance=[math]::Sqrt($dx*$dx+$dy*$dy+$dz*$dz);if($distance -le 4.2){$reachable+=[pscustomobject]@{block=[string]$cell.block;position=$cell.position;eyeDistance=$distance}}}
  return @($reachable|Sort-Object eyeDistance|Select-Object -First 1)
}
try {
  $keep=Get-ChildItem (Join-Path $root '..\KeepFocus\build\libs') -Filter 'keep_focus-*.jar'|Sort LastWriteTime -Descending|Select -First 1;if(!$keep){throw 'KeepFocus artifact not found'}
  $env:JAVA_HOME=$JavaHome;$env:Path="$JavaHome\bin;$env:Path";$env:LODESTONE_TOKEN=$token;$env:LODESTONE_PORT="$Port";$env:LODESTONE_PERMISSIONS='observe,communicate,control-player,modify-world,capture-screen'
  $runDir=Join-Path $root "verification\runs\primitive-$run";New-Item -ItemType Directory -Force -Path $runDir|Out-Null
  $cmd='.\gradlew.bat --no-daemon --console=plain -DkeepFocusArtifact="'+$keep.FullName+'" -Dlodestone.runDirectory="'+$runDir+'" -Dlodestone.port='+$Port+' -Dlodestone.token='+$token+' -Dlodestone.permissions='+$env:LODESTONE_PERMISSIONS+' :hosts:neoforge:mc1_21_1:runKeepFocusClient'
  $launcher=Start-Process cmd.exe -ArgumentList '/d','/c',$cmd -WorkingDirectory $root -WindowStyle Hidden -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath -PassThru
  for($i=0;$i-lt 1800;$i++){if(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue){break};if($launcher.HasExited){throw "launcher exited $($launcher.ExitCode)"};Start-Sleep 1};if(!(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)){throw 'MCP endpoint did not open'}
  $init=Rpc 'initialize' @{protocolVersion='2025-11-25';clientInfo=@{name='primitive-survival-house-axe';version='1.0'}};if(!$init.json.result){throw 'initialize failed'};Rpc 'notifications/initialized' @{}|Out-Null
  $tools=Rpc 'tools/list' @{};Log @{kind='tools-list';httpStatus=$tools.http;tools=@($tools.json.result.tools|% name)};if(!$tools.json.result){throw 'tools/list failed'}
  $toolNames=@($tools.json.result.tools|% name);if($toolNames -notcontains 'minecraft_subactions_execute'){throw 'BRIDGE_ERROR:minecraft_subactions_execute is absent from tools/list'}
  $r.toolNames=$toolNames|?{$_ -in @('ui_state','ui_navigate','lodestone_capability_invoke','minecraft_subactions_execute')}
  $catalog=Must (Call 'lodestone_capabilities_list' @{}) 'lodestone_capabilities_list';$r.negotiatedCapabilityCount=@($catalog.capabilities).Count
  $s=WaitUiReady;if($s.inWorld){throw 'refusing existing world'}
  if([string]$s.screenClass -match 'AccessibilityOnboardingScreen'){
    $continue=@($s.widgets|?{$_.label -eq 'Continue' -and $_.active -and $_.visible}|Select-Object -First 1);if(!$continue){throw 'Accessibility onboarding Continue button was not exposed'}
    Cap 'minecraft.ui.click' @{screenToken=[string]$s.screenToken;snapshotRevision=[string]$s.snapshotRevision;nodeId=[string]$continue[0].nodeId;button=0} '2.0'|Out-Null
    for($i=0;$i-lt 120;$i++){$s=Ui;if([string]$s.screenClass -match 'TitleScreen'){break};Start-Sleep -Milliseconds 250};if([string]$s.screenClass -notmatch 'TitleScreen'){throw 'main menu did not appear after accessibility onboarding'}
    $r.predicates.accessibilityOnboardingDismissed=$true
  }
  Must (Call 'ui_navigate' @{target='singleplayer'}) 'singleplayer'|Out-Null;WaitScreen 'SelectWorld|WorldSelection'|Out-Null;Must (Call 'ui_navigate' @{target='create_new_world'}) 'create world'|Out-Null;WaitScreen 'CreateWorld'|Out-Null;Must (Call 'ui_navigate' @{target='create_world'}) 'new Survival world'|Out-Null;WaitWorld|Out-Null;$r.predicates.survival=$true;$r.predicates.freshWorld=$true
  $r.predicates.startPlayer=Cap 'minecraft.player.state.read'
  $tree=$null;$origin=Cap 'minecraft.player.state.read';$treeProbeBudget=24
  for($probe=0;$probe-lt $treeProbeBudget -and !$tree;$probe++){
    $scanPlayer=Cap 'minecraft.player.state.read';$scanX=[math]::Floor([double]$scanPlayer.position.x);$scanY=[math]::Floor([double]$scanPlayer.position.y);$scanZ=[math]::Floor([double]$scanPlayer.position.z)
    # One rate-limited observation per reached waypoint. 16^3 stays inside the primitive read volume limit and covers both horizontal axes.
    try{$scan=Cap 'minecraft.world.blocks.read' @{x=$scanX-8;y=$scanY-4;z=$scanZ-8;sizeX=16;sizeY=16;sizeZ=16};$logsInScan=@($scan.blocks|?{$_.loaded -and $_.block -match 'minecraft:.*_log$'}|Sort-Object @{Expression={([double]$_.position.x-$scanPlayer.position.x)*([double]$_.position.x-$scanPlayer.position.x)+([double]$_.position.y-$scanPlayer.position.y)*([double]$_.position.y-$scanPlayer.position.y)+([double]$_.position.z-$scanPlayer.position.z)*([double]$_.position.z-$scanPlayer.position.z)}});if($logsInScan){$hit=$logsInScan[0];$tree=[pscustomobject]@{block=[string]$hit.block;position=$hit.position;discoveredBy='minecraft.world.blocks.read'}}}catch{Log @{kind='replan';stage='tree-region-scan';probe=$probe;message=$_.Exception.Message}}
    if($tree){break}
    # Expanding square spiral. Every search leg goes through the same 3D safe-waypoint planner;
    # discovery never falls back to blind forward movement.
    $directions=@(@(1,0),@(0,1),@(-1,0),@(0,-1));$dir=$directions[$probe%4];$ring=[int](1+[math]::Floor($probe/4));$beforeTravel=Cap 'minecraft.player.state.read'
    $searchPoint=[pscustomobject]@{x=[double]$origin.position.x+[int]$dir[0]*($ring*16);y=[double]$origin.position.y;z=[double]$origin.position.z+[int]$dir[1]*($ring*16)}
    try{MoveNear $searchPoint 3.0 12|Out-Null}catch{Log @{kind='replan';stage='tree-waypoint';probe=$probe;target=$searchPoint;message=$_.Exception.Message}}
    $afterTravel=Cap 'minecraft.player.state.read';Log @{kind='tree-waypoint';probe=$probe;target=$searchPoint;before=$beforeTravel.position;after=$afterTravel.position}
    Start-Sleep -Milliseconds 300
  }
  if(!$tree){throw "no loaded natural tree found after $treeProbeBudget bounded primitive spiral observations"};$r.predicates.treeFound=$true;$r.tree=$tree;$r.treeSearchProbes=$probe
  MoveNear $tree.position 3.0 40|Out-Null
  $logs=0;$obstructions=0;for($n=0;$n-lt 36;$n++){if((ItemCount $tree.block) -ge 3){break};try{
    $reachable=@(FindReachableLog $tree.block)
    if(!$reachable){$f=Cap 'minecraft.world.block.find' @{block=$tree.block;maxDistance=12;maxVisited=20000};if(!$f.found){$sp=Cap 'minecraft.player.state.read';$searchAngle=$n*2.399963;$searchPoint=[pscustomobject]@{x=[double]$sp.position.x+[math]::Cos($searchAngle)*14;y=[double]$sp.position.y;z=[double]$sp.position.z+[math]::Sin($searchAngle)*14};try{MoveNear $searchPoint 1.5 24|Out-Null}catch{Log @{kind='replan';stage='tree-gather-exploration';target=$searchPoint;message=$_.Exception.Message}};Start-Sleep -Milliseconds 300;continue};try{MoveNear $f.target.position .55 20|Out-Null}catch{Log @{kind='replan';stage='tree-reach-approach';target=$f.target.position;message=$_.Exception.Message}};Start-Sleep -Milliseconds 300;$reachable=@(FindReachableLog $tree.block)}
    if(!$reachable){Log @{kind='replan';stage='tree-reach';message='no log within verified 4.2-block eye range'};continue};$candidate=$reachable[0]
    MoveNear $candidate.position 2.7 10|Out-Null;$live=Cap 'minecraft.player.state.read';$edx=([double]$candidate.position.x+.5)-[double]$live.position.x;$edy=([double]$candidate.position.y+.5)-([double]$live.position.y+1.62);$edz=([double]$candidate.position.z+.5)-[double]$live.position.z;$eyeDistance=[math]::Sqrt($edx*$edx+$edy*$edy+$edz*$edz);if($eyeDistance -gt 4.2){Log @{kind='replan';stage='tree-reach';eyeDistance=$eyeDistance;target=$candidate.position};continue}
    $aim=Cap 'minecraft.player.block.look-at' @{x=[int]$candidate.position.x;y=[int]$candidate.position.y;z=[int]$candidate.position.z};$aimPos=$aim.target.blockPosition;$aimMatches=($aim.target.block -eq $tree.block -and [int]$aimPos.x -eq [int]$candidate.position.x -and [int]$aimPos.y -eq [int]$candidate.position.y -and [int]$aimPos.z -eq [int]$candidate.position.z)
    if(!$aimMatches){if($aim.target.block -match '_leaves$' -and [double]$aim.target.distance -le 4.2){$mine=Cap 'minecraft.player.block.mine' @{};$obstructions++;Log @{kind='replan';stage='mine-tree-obstruction';wanted=$tree.block;aim=$aim.target;mined=$mine.beforeBlock}};continue}
    $mine=Cap 'minecraft.player.block.mine' @{};if([string]$mine.beforeBlock -eq [string]$tree.block){$logs++;try{MoveNear $candidate.position .9 10|Out-Null}catch{};CollectNearbyDrops $tree.block 'post-log-mine'}
  }catch{Log @{kind='replan';stage='mine-tree';message=$_.Exception.Message};Start-Sleep -Milliseconds 300}}
  if((ItemCount $tree.block) -lt 3){$pickupPoints=@([pscustomobject]@{x=[double]$tree.position.x;y=[double]$tree.position.y;z=[double]$tree.position.z},[pscustomobject]@{x=[double]$tree.position.x+1.4;y=[double]$tree.position.y;z=[double]$tree.position.z},[pscustomobject]@{x=[double]$tree.position.x-1.4;y=[double]$tree.position.y;z=[double]$tree.position.z},[pscustomobject]@{x=[double]$tree.position.x;y=[double]$tree.position.y;z=[double]$tree.position.z+1.4},[pscustomobject]@{x=[double]$tree.position.x;y=[double]$tree.position.y;z=[double]$tree.position.z-1.4});foreach($pickup in $pickupPoints){try{MoveNear $pickup .35 12|Out-Null;Start-Sleep -Milliseconds 900}catch{};if((ItemCount $tree.block) -ge 3){break}}}
  $r.predicates.treeMined=((ItemCount $tree.block) -ge 1);if((ItemCount $tree.block) -lt 3){throw "tree mining collected fewer than three recipe-resource logs after $logs confirmed log breaks and $obstructions obstruction clears"};$r.predicates.logsMined=$logs;$r.predicates.treeObstructionsCleared=$obstructions
  OpenInventoryBounded|Out-Null;$inv=Cap 'minecraft.inventory.container.read';$logSlot=@($inv.slots|?{$_.item -eq $tree.block}|Select -First 1).slot;if($null -eq $logSlot){$logSlot=@($inv.slots|?{$_.item -match '_log$'}|Select -First 1).slot};if($null -eq $logSlot){throw 'mined log not visible in inventory'}
  ClickFresh ([int]$logSlot) 0 'PICKUP' 'pick natural log for planks'|Out-Null
  ClickFresh 1 0 'PICKUP' 'place log in player crafting grid'|Out-Null
  ClickFresh 0 0 'QUICK_MOVE' 'quick-move visible plank output'|Out-Null
  if((ItemCount ($tree.block -replace '_log$','_planks')) -lt 4){throw 'visible log recipe did not produce planks in inventory'};$r.predicates.visiblePlanksCrafted=$true
  Start-Sleep -Milliseconds 250

  # Visible 2x2 crafting-table recipe: slots 1,2,3,4. Every click reads a fresh revision.
  $inv=ReadContainer;$plankRows=@($inv.slots|?{$_.item -match '_planks$' -and [int]$_.slot -ge 9}|Select-Object -First 1);if(!$plankRows){throw 'planks missing from player inventory before table recipe'};$plankSlot=[int]$plankRows[0].slot
  ClickFresh $plankSlot 0 'PICKUP' 'pick planks for table'|Out-Null
  foreach($grid in 1,2,3,4){ClickFresh $grid 1 'PICKUP' "table grid $grid"|Out-Null}
  ClickFresh $plankSlot 0 'PICKUP' 'return unused table planks'|Out-Null
  ClickFresh 0 0 'QUICK_MOVE' 'take crafting table'|Out-Null
  if((ItemCount 'minecraft:crafting_table') -lt 1){throw 'visible table recipe did not produce crafting table'};$r.predicates.visibleCraftingTableCrafted=$true

  # Visible 2x2 stick recipe: slots 1 and 3.
  $inv=ReadContainer;$plankRows=@($inv.slots|?{$_.item -match '_planks$' -and [int]$_.slot -ge 9}|Select-Object -First 1);if(!$plankRows){throw 'planks missing from player inventory before stick recipe'};$plankSlot=[int]$plankRows[0].slot
  ClickFresh $plankSlot 0 'PICKUP' 'pick planks for sticks'|Out-Null
  foreach($grid in 1,3){ClickFresh $grid 1 'PICKUP' "stick grid $grid"|Out-Null}
  ClickFresh $plankSlot 0 'PICKUP' 'return unused stick planks'|Out-Null
  ClickFresh 0 0 'QUICK_MOVE' 'take sticks'|Out-Null
  if((ItemCount 'minecraft:stick') -lt 4){throw 'visible stick recipe did not produce four sticks'};$r.predicates.visibleSticksCrafted=$true

  # Put table in hotbar through visible quick-move when needed, close inventory, place by normal use.
  $hot=Inventory;$tableHot=@($hot.slots|?{[int]$_.slot -le 8 -and $_.item -eq 'minecraft:crafting_table'}|Select-Object -First 1)
  if(!$tableHot){$tableSlot=ContainerItemSlot 'minecraft:crafting_table' 9;if($tableSlot -lt 0){throw 'crafting table missing from visible container'};ClickFresh $tableSlot 0 'QUICK_MOVE' 'table to hotbar'|Out-Null}
  Cap 'minecraft.ui.key' @{key=256;scanCode=0;modifiers=0}|Out-Null;WaitWorld|Out-Null
  $tableHotbar=EnsureHotbar 'minecraft:crafting_table';Cap 'minecraft.inventory.slot.select' @{slot=$tableHotbar}|Out-Null
  $p=Cap 'minecraft.player.state.read';$px=[math]::Floor([double]$p.position.x);$pz=[math]::Floor([double]$p.position.z)
  $hm=Cap 'minecraft.world.heightmap.read' @{x=$px-4;z=$pz-4;sizeX=9;sizeZ=9;includeSurfaceBlocks=$false}
  $tableTarget=$null
  foreach($col in @($hm.columns|?{$_.loaded -and !$_.empty}|Sort-Object @{Expression={[math]::Abs([int]$_.x-$px)+[math]::Abs([int]$_.z-$pz)}})){
    $dd=[math]::Sqrt(([int]$col.x-$p.position.x)*([int]$col.x-$p.position.x)+([int]$col.z-$p.position.z)*([int]$col.z-$p.position.z));if($dd -lt 1.8 -or $dd -gt 3.8){continue}
    $y=[int]$col.height;$cells=Cap 'minecraft.world.blocks.read' @{x=[int]$col.x;y=$y-1;z=[int]$col.z;sizeX=1;sizeY=2;sizeZ=1}
    $lower=@($cells.blocks|?{[int]$_.position.y -eq $y-1})[0];$upper=@($cells.blocks|?{[int]$_.position.y -eq $y})[0]
    if($lower.loaded -and !$lower.air -and $upper.air){$tableTarget=[pscustomobject]@{x=[int]$col.x;y=$y;z=[int]$col.z};break}
  }
  if(!$tableTarget){throw 'no clear in-reach crafting-table placement cell'}
  MoveNear $tableTarget 3.2|Out-Null;Cap 'minecraft.player.target-block.place' @{x=$tableTarget.x;y=$tableTarget.y;z=$tableTarget.z;face='up';item='minecraft:crafting_table'}|Out-Null
  $tableRead=Cap 'minecraft.world.block.read' @{x=$tableTarget.x;y=$tableTarget.y;z=$tableTarget.z};if($tableRead.block -ne 'minecraft:crafting_table'){throw 'crafting table placement readback failed'};$r.predicates.tablePlacedByPrimitive=$true
  $foundTable=Cap 'minecraft.world.block.find' @{block='minecraft:crafting_table';maxDistance=6;maxVisited=4096};if(!$foundTable.found){throw 'placed crafting table not found'}
  $tp=$foundTable.target.position;Cap 'minecraft.player.block.look-at' @{x=[int]$tp.x;y=[int]$tp.y;z=[int]$tp.z;blockFingerprint=[string]$foundTable.target.blockFingerprint}|Out-Null;Cap 'minecraft.player.interact' @{action='use'}|Out-Null;WaitScreen 'CraftingScreen'|Out-Null

  # Visible 3x3 wooden axe recipe: planks 1,2,4; sticks 5,8.
  $inv=ReadContainer;$plankSlot=[int](@($inv.slots|?{$_.item -match '_planks$' -and [int]$_.slot -ge 10}|Select-Object -First 1)[0].slot);$stickSlot=[int](@($inv.slots|?{$_.item -eq 'minecraft:stick' -and [int]$_.slot -ge 10}|Select-Object -First 1)[0].slot)
  if($plankSlot -lt 0 -or $stickSlot -lt 0){throw 'axe ingredients missing in visible crafting table'}
  ClickFresh $plankSlot 0 'PICKUP' 'pick axe planks'|Out-Null
  foreach($grid in 1,2,4){ClickFresh $grid 1 'PICKUP' "axe plank $grid"|Out-Null}
  ClickFresh $plankSlot 0 'PICKUP' 'return axe planks'|Out-Null
  ClickFresh $stickSlot 0 'PICKUP' 'pick axe sticks'|Out-Null
  foreach($grid in 5,8){ClickFresh $grid 1 'PICKUP' "axe stick $grid"|Out-Null}
  ClickFresh $stickSlot 0 'PICKUP' 'return axe sticks'|Out-Null
  ClickFresh 0 0 'QUICK_MOVE' 'take wooden axe'|Out-Null
  if((ItemCount 'minecraft:wooden_axe') -lt 1){throw 'visible axe recipe did not produce wooden axe'};$r.predicates.woodenAxeCrafted=$true;$r.predicates.visibleAxeRecipe=$true
  $hot=Inventory;$axeHot=@($hot.slots|?{[int]$_.slot -le 8 -and $_.item -eq 'minecraft:wooden_axe'}|Select-Object -First 1)
  if(!$axeHot){$axeSlot=ContainerItemSlot 'minecraft:wooden_axe' 10;if($axeSlot -lt 0){throw 'wooden axe missing from visible table container'};ClickFresh $axeSlot 0 'QUICK_MOVE' 'axe to hotbar'|Out-Null}
  Cap 'minecraft.ui.key' @{key=256;scanCode=0;modifiers=0}|Out-Null;WaitWorld|Out-Null
  $axeHotbar=EnsureHotbar 'minecraft:wooden_axe';Cap 'minecraft.inventory.hotbar.select-item' @{item='minecraft:wooden_axe';preferredSlot=$axeHotbar}|Out-Null
  $ctx=Cap 'minecraft.player.context.read' @{reach=16};if($ctx.heldItem.id -ne 'minecraft:wooden_axe' -or $ctx.gameMode -ne 'survival'){throw 'wooden axe or Survival mode readback failed'};$r.predicates.woodenAxeEquipped=$true;$r.predicates.survival=$true

  # Gather natural dirt/grass drops through explicit look + held mining. Never use inventory mutation.
  $dirtNeeded=28;$p=Cap 'minecraft.player.state.read';$mx=[math]::Floor([double]$p.position.x);$mz=[math]::Floor([double]$p.position.z)
  $hm=Cap 'minecraft.world.heightmap.read' @{x=$mx-9;z=$mz-9;sizeX=19;sizeZ=19;includeSurfaceBlocks=$false};$minedDirt=@()
  foreach($col in @($hm.columns|?{$_.loaded -and !$_.empty}|Sort-Object @{Expression={([int]$_.x-$mx)*([int]$_.x-$mx)+([int]$_.z-$mz)*([int]$_.z-$mz)}})){
    if((ItemCount 'minecraft:dirt') -ge $dirtNeeded){break};$target=[pscustomobject]@{x=[int]$col.x;y=[int]$col.height-1;z=[int]$col.z}
    $ps=Cap 'minecraft.player.state.read';if([math]::Floor([double]$ps.position.x) -eq $target.x -and [math]::Floor([double]$ps.position.z) -eq $target.z){continue}
    $b=Cap 'minecraft.world.block.read' @{x=$target.x;y=$target.y;z=$target.z};if($b.block -notin @('minecraft:grass_block','minecraft:dirt')){continue}
    try{MoveNear $target 1.7 12|Out-Null;Cap 'minecraft.player.block.look-at' @{x=$target.x;y=$target.y;z=$target.z}|Out-Null;Cap 'minecraft.player.block.mine' @{}|Out-Null;Start-Sleep -Milliseconds 450;$minedDirt+=@{x=$target.x;y=$target.y;z=$target.z;before=$b.block}}catch{Log @{kind='replan';stage='gather-dirt';target=$target;message=$_.Exception.Message}}
  }
  $dirtCount=ItemCount 'minecraft:dirt';if($dirtCount -lt $dirtNeeded){throw "only gathered $dirtCount dirt; needed $dirtNeeded"};$r.predicates.dirtGathered=$dirtCount;$r.dirtMined=$minedDirt
  $dirtHotbar=EnsureHotbar 'minecraft:dirt';Cap 'minecraft.inventory.hotbar.select-item' @{item='minecraft:dirt';preferredSlot=$dirtHotbar}|Out-Null

  # Select a fresh flat 3x3 site and build two-high perimeter, two-block doorway, full roof.
  $p=Cap 'minecraft.player.state.read';$sx=[math]::Floor([double]$p.position.x);$sz=[math]::Floor([double]$p.position.z);$hm=Cap 'minecraft.world.heightmap.read' @{x=$sx-12;z=$sz-12;sizeX=25;sizeZ=25;includeSurfaceBlocks=$false}
  $height=@{};foreach($c in $hm.columns){if($c.loaded -and !$c.empty){$height["$($c.x),$($c.z)"]=[int]$c.height}}
  $sites=@();for($x=$sx-10;$x-le $sx+10;$x++){for($z=$sz-10;$z-le $sz+10;$z++){$vals=@();for($dx=-1;$dx-le 1;$dx++){for($dz=-1;$dz-le 1;$dz++){$key="$($x+$dx),$($z+$dz)";if($height.ContainsKey($key)){$vals+=$height[$key]}}};if($vals.Count -eq 9 -and (@($vals|Sort-Object -Unique)).Count -eq 1){$d=[math]::Sqrt(($x-$p.position.x)*($x-$p.position.x)+($z-$p.position.z)*($z-$p.position.z));if($d -ge 4 -and $d -le 10){$sites+=[pscustomobject]@{x=$x;y=[int]$vals[0];z=$z;distance=$d}}}}}
  $site=@($sites|Sort-Object distance|Select-Object -First 1);if(!$site){throw 'no flat loaded 3x3 house site found'};$site=$site[0];MoveNear $site .75 30|Out-Null
  $doorX=[int]$site.x;$doorZ=[int]$site.z+1;$placed=@()
  foreach($dy in 0,1){foreach($dx in -1..1){foreach($dz in -1..1){if([math]::Abs($dx)-ne 1 -and [math]::Abs($dz)-ne 1){continue};$x=[int]$site.x+$dx;$z=[int]$site.z+$dz;if($x -eq $doorX -and $z -eq $doorZ){continue};Cap 'minecraft.player.target-block.place' @{x=$x;y=[int]$site.y+$dy;z=$z;face='up';item='minecraft:dirt'}|Out-Null;$placed+=@{x=$x;y=[int]$site.y+$dy;z=$z;part='wall'}}}}
  foreach($dx in -1..1){foreach($dz in -1..1){if($dx -eq 0 -and $dz -eq 0){continue};$x=[int]$site.x+$dx;$z=[int]$site.z+$dz;Cap 'minecraft.player.target-block.place' @{x=$x;y=[int]$site.y+2;z=$z;face='up';item='minecraft:dirt'}|Out-Null;$placed+=@{x=$x;y=[int]$site.y+2;z=$z;part='roof'}}}
  Cap 'minecraft.player.target-block.place' @{x=[int]$site.x;y=[int]$site.y+2;z=[int]$site.z;face='east';item='minecraft:dirt'}|Out-Null;$placed+=@{x=[int]$site.x;y=[int]$site.y+2;z=[int]$site.z;part='roof'}
  $houseRead=Cap 'minecraft.world.blocks.read' @{x=[int]$site.x-1;y=[int]$site.y;z=[int]$site.z-1;sizeX=3;sizeY=3;sizeZ=3};$bad=@()
  foreach($expect in $placed){$cell=@($houseRead.blocks|?{[int]$_.position.x -eq $expect.x -and [int]$_.position.y -eq $expect.y -and [int]$_.position.z -eq $expect.z})[0];if($cell.block -ne 'minecraft:dirt'){$bad+=$expect}}
  $doorCells=@($houseRead.blocks|?{[int]$_.position.x -eq $doorX -and [int]$_.position.z -eq $doorZ -and [int]$_.position.y -in @([int]$site.y,[int]$site.y+1)})
  if($bad.Count -gt 0 -or @($doorCells|?{!$_.air}).Count -gt 0){throw "house terminal readback failed: bad=$($bad.Count) doorwayBlocked=$(@($doorCells|?{!$_.air}).Count)"}
  $r.house=[ordered]@{center=@{x=$site.x;y=$site.y;z=$site.z};wallAndRoofBlocks=$placed.Count;doorway=@{x=$doorX;z=$doorZ;height=2};readback=$houseRead};$r.predicates.housePlaced=$true;$r.predicates.houseWalls=$true;$r.predicates.houseRoof=$true;$r.predicates.houseDoorway=$true

  # Leave doorway, observe only naturally loaded entities, and attack via ordinary axe input.
  $exit=[pscustomobject]@{x=[int]$site.x;y=[int]$site.y;z=[int]$site.z+5};MoveNear $exit 1.0 20|Out-Null
  Cap 'minecraft.inventory.hotbar.select-item' @{item='minecraft:wooden_axe';preferredSlot=$axeHotbar}|Out-Null
  $hostiles=@('minecraft:zombie','minecraft:husk','minecraft:drowned','minecraft:spider','minecraft:cave_spider','minecraft:skeleton','minecraft:stray','minecraft:slime','minecraft:creeper','minecraft:witch','minecraft:pillager','minecraft:enderman','minecraft:phantom')
  $combatStart=[DateTime]::UtcNow;$killed=$false;$targetMob=$null;$attackCount=0;$lastAttackUtc=$null
  for($poll=0;$poll-lt 540 -and !$killed;$poll++){
    $ps=Cap 'minecraft.player.state.read';if([double]$ps.health -le 6){throw 'health safety floor reached during hostile search/combat'}
    $near=Cap 'minecraft.entity.nearby.read' @{radius=96;limit=256;includePlayers=$false};$candidates=@($near.entities|?{$_.type -in $hostiles -and [math]::Abs([double]$_.position.y-[double]$ps.position.y) -le 7})
    if(!$targetMob -and $candidates){$targetMob=@($candidates|Sort-Object @{Expression={switch($_.type){'minecraft:zombie'{0};'minecraft:husk'{1};'minecraft:spider'{2};'minecraft:drowned'{3};default{5}}}},distance|Select-Object -First 1)[0];$r.naturalHostileObserved=$targetMob;Log @{kind='predicate';name='natural-hostile-observed';entity=$targetMob}}
    if($targetMob){
      $current=@($near.entities|?{$_.uuid -eq $targetMob.uuid}|Select-Object -First 1)
      if(!$current){if($attackCount -gt 0 -and $lastAttackUtc -and (([DateTime]::UtcNow-$lastAttackUtc).TotalSeconds -lt 5)){$killed=$true;break}else{$targetMob=$null;$attackCount=0;continue}}
      $mob=$current[0]
      if([double]$mob.distance -gt 2.8){try{MoveNear $mob.position 2.2 6|Out-Null}catch{$targetMob=$null;$attackCount=0};continue}
      AimAtPoint $mob.position .9;Cap 'minecraft.player.interact' @{action='attack'}|Out-Null;$attackCount++;$lastAttackUtc=[DateTime]::UtcNow;Log @{kind='combat-attack';targetUuid=$mob.uuid;targetType=$mob.type;distance=$mob.distance;weapon='minecraft:wooden_axe';attack=$attackCount};Start-Sleep -Milliseconds 1350
    }else{
      if($poll%15 -eq 14){$angle=($poll/15)*1.570796;$wander=[pscustomobject]@{x=[double]$ps.position.x+[math]::Cos($angle)*8;y=[double]$ps.position.y;z=[double]$ps.position.z+[math]::Sin($angle)*8};try{MoveNear $wander 1.5 10|Out-Null}catch{}}
      if($poll%30 -eq 0){$info=Cap 'minecraft.server.info.read';Write-Output "hostile-search poll=$poll dayTime=$($info.dayTime) elapsed=$([int]([DateTime]::UtcNow-$combatStart).TotalSeconds)s"}
      Start-Sleep -Seconds 2
    }
  }
  if(!$killed){throw 'no naturally loaded hostile mob could be confirmed killed within bounded search/combat window'}
  $postNear=Cap 'minecraft.entity.nearby.read' @{radius=96;limit=256;includePlayers=$false};$postServer=Cap 'minecraft.entity.list' @{dimension='minecraft:overworld';limit=256;includePlayers=$false}
  if(@($postNear.entities|?{$_.uuid -eq $targetMob.uuid}).Count -gt 0 -or @($postServer.entities|?{$_.uuid -eq $targetMob.uuid}).Count -gt 0){throw 'target hostile still present after combat'}
  $ctx=Cap 'minecraft.player.context.read' @{reach=16};$finalInv=Inventory;$finalPlayer=Cap 'minecraft.player.state.read'
  if($ctx.heldItem.id -ne 'minecraft:wooden_axe'){throw 'wooden axe was not held at terminal combat readback'}
  $r.combat=[ordered]@{target=$targetMob;attacks=$attackCount;weapon=$ctx.heldItem.id;targetAbsentNearby=$true;targetAbsentServer=$true;postNearby=$postNear;postServer=$postServer}
  $r.predicates.mobKilledWithAxe=$true;$r.predicates.playerAlive=([double]$finalPlayer.health -gt 0);$r.predicates.commandsUsed=$false;$r.predicates.directWorldMutationUsed=$false;$r.finalPlayer=$finalPlayer;$r.finalInventory=$finalInv;$r.finalContext=$ctx
  if(!$r.predicates.playerAlive){throw 'player died before terminal verification'}
  $r.status='PASS'
}catch{ $r.status='FAIL';$r.errors+= $_.Exception.Message; throw }finally{try{Cap 'minecraft.input.release-all' @{}|Out-Null}catch{};if($launcher){try{& taskkill.exe /PID $launcher.Id /T /F|Out-Null}catch{}};Start-Sleep -Milliseconds 500;$r.listenerAfterCleanup=[bool](Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue);$r.completedAtUtc=[DateTime]::UtcNow.ToString('o');$r|ConvertTo-Json -Depth 40|Set-Content $reportPath -Encoding UTF8;$http.Dispose()}
[pscustomobject]@{status=$r.status;reportPath=$reportPath;evidencePath=$evidencePath}
