param([ValidateSet('Start','Stop','Pause')][string]$Action='Start')
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$runtimeDir=Join-Path $projectRoot 'var/runtime'
$settingsFile=Join-Path $runtimeDir 'settings.json'
$stateFile=Join-Path $runtimeDir 'processes.json'
New-Item -ItemType Directory -Path $runtimeDir -Force | Out-Null
Set-Location -LiteralPath $projectRoot
function New-RandomHex { $bytes=New-Object byte[] 32; $rng=[Security.Cryptography.RandomNumberGenerator]::Create(); $rng.GetBytes($bytes); $rng.Dispose(); return ([BitConverter]::ToString($bytes)).Replace('-','').ToLowerInvariant() }
function Find-MySql {
 if($env:MYSQL_BIN -and (Test-Path -LiteralPath (Join-Path $env:MYSQL_BIN 'mysqld.exe'))){return $env:MYSQL_BIN}
 $command=Get-Command mysqld.exe -ErrorAction SilentlyContinue
 if($command){return Split-Path -Parent $command.Source}
 if(Test-Path -LiteralPath 'C:/wamp64/bin/mysql'){
  foreach($candidate in (Get-ChildItem -LiteralPath 'C:/wamp64/bin/mysql' -Directory | Sort-Object Name -Descending)){
   $binary=Join-Path $candidate.FullName 'bin';if(Test-Path -LiteralPath (Join-Path $binary 'mysqld.exe')){return $binary}
  }
 }
 throw 'MySQL 8.4 is required. Set MYSQL_BIN to the directory containing mysqld.exe.'
}
function Is-Running([int]$processId){return $processId -gt 0 -and $null -ne (Get-Process -Id $processId -ErrorAction SilentlyContinue)}
function Is-Listening([int]$port){$client=New-Object Net.Sockets.TcpClient;try{$task=$client.ConnectAsync('127.0.0.1',$port);if(!$task.Wait(500)){return $false};return $client.Connected}catch{return $false}finally{$client.Dispose()}}
function Save-State { $script:processState | ConvertTo-Json | Set-Content -LiteralPath $stateFile -Encoding UTF8 }
function Start-Owned([string]$exe,[string[]]$arguments,[string]$label){
 $identityFile=Join-Path $runtimeDir ($label+'.pid');if(Test-Path -LiteralPath $identityFile){Remove-Item -LiteralPath $identityFile}
 $process=Start-Process -FilePath $exe -ArgumentList $arguments -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtimeDir ($label+'.log')) -RedirectStandardError (Join-Path $runtimeDir ($label+'.error.log'))
 $script:processState[$label]=$process.Id
 $script:processState[$label+'StartedAt']=$process.StartTime.ToUniversalTime().Ticks.ToString()
 Save-State;return $process.Id
}
function Query-Database([string]$statement){
 $previous=$env:MYSQL_PWD
 try{$env:MYSQL_PWD=$script:config.rootPassword;$result=$statement | & (Join-Path $script:mysqlBin 'mysql.exe') --no-defaults --host=127.0.0.1 "--port=$($script:config.dbPort)" --user=root --batch --skip-column-names 2>&1;if($LASTEXITCODE -ne 0){throw 'Unable to authenticate to the managed database.'};return ($result -join "`n").Trim()}finally{$env:MYSQL_PWD=$previous}
}
$script:processState=@{}
if(Test-Path -LiteralPath $stateFile){$loaded=Get-Content -Raw -LiteralPath $stateFile | ConvertFrom-Json;foreach($property in $loaded.PSObject.Properties){$script:processState[$property.Name]=$property.Value}}
$script:mysqlBin=Find-MySql
if(Test-Path -LiteralPath $settingsFile){$script:config=Get-Content -Raw -LiteralPath $settingsFile | ConvertFrom-Json}
if($Action -in @('Stop','Pause')){
 foreach($label in @('web','api')){
  $processId=[int]$script:processState[$label]
  if(Is-Running $processId){
   $tracked=Get-Process -Id $processId
   $sameBirth=$script:processState.ContainsKey($label+'StartedAt') -and $tracked.StartTime.ToUniversalTime().Ticks.ToString() -eq $script:processState[$label+'StartedAt']
   $command=$null
   if(!$sameBirth){$command=(Get-CimInstance Win32_Process -Filter "ProcessId = $processId").CommandLine}
   $expected=if($label -eq 'web'){'dev-server.cjs'}else{'enterpriseos-api-0.1.0.jar'}
   if($sameBirth -or ($command -and $command.Contains($expected) -and $command.Replace('/','\').ToLowerInvariant().Contains($projectRoot.Replace('/','\').ToLowerInvariant()))){Stop-Process -Id $processId;Write-Host "Stopped $label."}else{throw "Process identity changed; refusing to stop $label process $processId."}
  }
  $script:processState.Remove($label)
  $script:processState.Remove($label+'StartedAt')
  $identityFile=Join-Path $runtimeDir ($label+'.pid');if(Test-Path -LiteralPath $identityFile){Remove-Item -LiteralPath $identityFile}
 }
 if($Action -eq 'Stop' -and (Is-Running ([int]$script:processState['mysql']))){
  $actual=Query-Database 'SELECT @@datadir;'
  if([IO.Path]::GetFullPath($actual).TrimEnd('\','/') -ne [IO.Path]::GetFullPath((Join-Path $runtimeDir 'mysql')).TrimEnd('\','/')){throw 'Refusing to stop an unrecognized database.'}
  $previous=$env:MYSQL_PWD
  try{$env:MYSQL_PWD=$script:config.rootPassword;& (Join-Path $script:mysqlBin 'mysqladmin.exe') --no-defaults --host=127.0.0.1 "--port=$($script:config.dbPort)" --user=root shutdown;if($LASTEXITCODE -ne 0){throw 'Database shutdown failed.'}}finally{$env:MYSQL_PWD=$previous}
  $script:processState.Remove('mysql');Write-Host 'Stopped local database. Data retained.'
 }
 Save-State;return
}
$java=(Get-Command java.exe -ErrorAction Stop).Source
$javaVersionOutput=& $java -version 2>&1 | Out-String
if($javaVersionOutput -notmatch 'version "(\d+)'){throw "Unable to determine Java runtime version from $java."}
$javaMajor=[int]$Matches[1]
if($javaMajor -lt 25){throw "Java 25 or newer is required to run EnterpriseOS. Found Java $javaMajor at $java."}
$node=(Get-Command node.exe -ErrorAction Stop).Source
if(!$script:config){$script:config=[PSCustomObject]@{dbPort=3309;apiPort=8080;webPort=5173;rootPassword=(New-RandomHex);dbPassword=(New-RandomHex);encryptionKey=(New-RandomHex)}}
if(!$script:config.encryptionKey){$script:config | Add-Member -NotePropertyName encryptionKey -NotePropertyValue (New-RandomHex) -Force}
$script:config | ConvertTo-Json | Set-Content -LiteralPath $settingsFile -Encoding UTF8
$dataDir=Join-Path $runtimeDir 'mysql'
if(Is-Listening $script:config.dbPort){
 $actual=Query-Database 'SELECT @@datadir;'
 if([IO.Path]::GetFullPath($actual).TrimEnd('\','/') -ne [IO.Path]::GetFullPath($dataDir).TrimEnd('\','/')){throw 'The configured database port belongs to another MySQL instance.'}
}else{
 if(!(Test-Path -LiteralPath (Join-Path $dataDir 'auto.cnf'))){
  New-Item -ItemType Directory -Path $dataDir -Force | Out-Null
  Write-Host 'Initializing a private MySQL directory for your application...'
  & (Join-Path $script:mysqlBin 'mysqld.exe') --no-defaults --initialize-insecure "--basedir=$(Split-Path -Parent $script:mysqlBin)" "--datadir=$dataDir"
  if($LASTEXITCODE -ne 0){throw 'Database initialization failed.'}
 }
 $initFile=Join-Path $runtimeDir 'initialize.sql'
 @"
ALTER USER 'root'@'localhost' IDENTIFIED BY '$($script:config.rootPassword)';
CREATE DATABASE IF NOT EXISTS enterpriseos CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'enterpriseos'@'127.0.0.1' IDENTIFIED BY '$($script:config.dbPassword)';
ALTER USER 'enterpriseos'@'127.0.0.1' IDENTIFIED BY '$($script:config.dbPassword)';
GRANT ALL PRIVILEGES ON enterpriseos.* TO 'enterpriseos'@'127.0.0.1';
"@ | Set-Content -LiteralPath $initFile -Encoding ASCII
 $null=Start-Owned (Join-Path $script:mysqlBin 'mysqld.exe') @('--no-defaults',"--basedir=`"$(Split-Path -Parent $script:mysqlBin)`"","--datadir=`"$dataDir`"","--port=$($script:config.dbPort)",'--bind-address=127.0.0.1','--mysqlx=OFF',"--init-file=`"$initFile`"",'--console') 'mysql'
 $ready=$false
 for($attempt=0;$attempt -lt 90;$attempt++){try{$null=Query-Database 'SELECT 1;';$ready=$true;break}catch{Start-Sleep -Seconds 1}}
 if(!$ready){throw 'MySQL did not start. Inspect var/runtime/mysql.error.log.'}
 Remove-Item -LiteralPath $initFile
}
$builtJar=Join-Path $projectRoot 'services/api/target/enterpriseos-api-0.1.0.jar'
$jar=Join-Path $runtimeDir 'enterpriseos-api-0.1.0.jar'
if(!(Test-Path -LiteralPath $builtJar)){throw 'Build the API first: mvn package -DskipTests from services/api.'}
if(!(Is-Listening $script:config.apiPort)){Copy-Item -LiteralPath $builtJar -Destination $jar -Force}
if(!(Test-Path -LiteralPath (Join-Path $projectRoot 'apps/web/dist/index.html'))){throw 'Build the frontend first: node scripts/build-web.cjs'}
if(Is-Listening $script:config.apiPort){if(!(Is-Running ([int]$script:processState['api']))){throw "API port $($script:config.apiPort) is already occupied."}}
else{
 $variables=@{DB_URL="jdbc:mysql://127.0.0.1:$($script:config.dbPort)/enterpriseos?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";DB_USERNAME='enterpriseos';DB_PASSWORD=$script:config.dbPassword;SERVER_ADDRESS='127.0.0.1';PORT="$($script:config.apiPort)";APP_SETUP_ENABLED='true';APP_SEED_DEMO='false';APP_ENCRYPTION_KEY=$script:config.encryptionKey;APP_ALLOWED_ORIGINS="http://localhost:$($script:config.webPort),http://127.0.0.1:$($script:config.webPort)";APP_PUBLIC_BASE_URL="http://localhost:$($script:config.webPort)";APP_MAIL_OUTBOX_DIR=(Join-Path $runtimeDir 'mail-outbox');APP_LOGO_DIR=(Join-Path $runtimeDir 'logos');APP_DOCUMENT_DIR=(Join-Path $runtimeDir 'documents')}
 $variables['APP_PID_FILE']=Join-Path $runtimeDir 'api.pid'
 $previous=@{};try{foreach($name in $variables.Keys){$previous[$name]=[Environment]::GetEnvironmentVariable($name,'Process');[Environment]::SetEnvironmentVariable($name,$variables[$name],'Process')};$null=Start-Owned $java @('-jar',"`"$jar`"") 'api'}finally{foreach($name in $previous.Keys){[Environment]::SetEnvironmentVariable($name,$previous[$name],'Process')}}
}
$ready=$false
for($attempt=0;$attempt -lt 90;$attempt++){try{$response=Invoke-RestMethod "http://127.0.0.1:$($script:config.apiPort)/health" -TimeoutSec 2;if($response.status -eq 'UP'){$ready=$true;break}}catch{};Start-Sleep -Seconds 1}
if(!$ready){throw 'The API did not start. Inspect var/runtime/api.log and api.error.log.'}
if(Test-Path -LiteralPath (Join-Path $runtimeDir 'api.pid')){$actualId=[int](Get-Content -LiteralPath (Join-Path $runtimeDir 'api.pid') -Raw);$actual=Get-Process -Id $actualId;$script:processState['api']=$actualId;$script:processState['apiStartedAt']=$actual.StartTime.ToUniversalTime().Ticks.ToString();Save-State}
if(Is-Listening $script:config.webPort){if(!(Is-Running ([int]$script:processState['web']))){
 $served=(Invoke-WebRequest "http://127.0.0.1:$($script:config.webPort)/assets/app.js" -UseBasicParsing -TimeoutSec 5).Content
 $local=[IO.File]::ReadAllText((Join-Path $projectRoot 'apps/web/dist/assets/app.js'))
 if($served -ne $local){throw "Frontend port $($script:config.webPort) belongs to another application."}
 Write-Host 'Reusing the existing frontend server for this exact project build; it will not be stopped by this launcher.'
}}
else{
 $previousPort=$env:WEB_PORT;$previousApi=$env:API_BASE_URL;$previousPid=$env:EOS_WEB_PID_FILE
 try{$env:WEB_PORT="$($script:config.webPort)";$env:API_BASE_URL="http://127.0.0.1:$($script:config.apiPort)";$env:EOS_WEB_PID_FILE=Join-Path $runtimeDir 'web.pid';$null=Start-Owned $node @("`"$(Join-Path $projectRoot 'scripts/dev-server.cjs')`"") 'web'}finally{$env:WEB_PORT=$previousPort;$env:API_BASE_URL=$previousApi;$env:EOS_WEB_PID_FILE=$previousPid}
 for($attempt=0;$attempt -lt 20;$attempt++){if(Test-Path -LiteralPath (Join-Path $runtimeDir 'web.pid')){break};Start-Sleep -Milliseconds 200}
 if(Test-Path -LiteralPath (Join-Path $runtimeDir 'web.pid')){$actualId=[int](Get-Content -LiteralPath (Join-Path $runtimeDir 'web.pid') -Raw);$actual=Get-Process -Id $actualId;$script:processState['web']=$actualId;$script:processState['webStartedAt']=$actual.StartTime.ToUniversalTime().Ticks.ToString();Save-State}
}
Write-Host "`nEnterpriseOS is running: http://localhost:$($script:config.webPort)"
Write-Host 'Create your private company accounts on the first-run setup screen. No sample data is inserted.'
Write-Host 'Stop with .\Stop-EnterpriseOS.ps1. Your data is retained.'
