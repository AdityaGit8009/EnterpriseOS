param([string]$VerifyFile='')
$ErrorActionPreference='Stop'
Set-Location -LiteralPath $PSScriptRoot
$runtimeDir=Join-Path $PSScriptRoot 'var/runtime'
$config=Get-Content -LiteralPath (Join-Path $runtimeDir 'settings.json') -Raw | ConvertFrom-Json
$mysqlBin=$env:MYSQL_BIN
if(!$mysqlBin){$mysqlBin=Join-Path ((Get-ChildItem -LiteralPath 'C:/wamp64/bin/mysql' -Directory | Sort-Object Name -Descending | Select-Object -First 1).FullName) 'bin'}
if(!(Test-Path -LiteralPath (Join-Path $mysqlBin 'mysqldump.exe'))){throw 'Set MYSQL_BIN to the installed MySQL bin folder.'}
$previousPassword=$env:MYSQL_PWD;$previousEncoding=$OutputEncoding
$OutputEncoding=New-Object Text.UTF8Encoding $false
$createdPassphrase=$false
if(!$env:EOS_BACKUP_PASSPHRASE){$secret=Read-Host 'Private backup passphrase (16+ characters)' -AsSecureString;$env:EOS_BACKUP_PASSPHRASE=[Net.NetworkCredential]::new('',$secret).Password;$createdPassphrase=$true}
if($env:EOS_BACKUP_PASSPHRASE.Length -lt 16){throw 'Backup passphrase must contain at least 16 characters.'}
$temporary=Join-Path $runtimeDir ('backup-work-'+[Guid]::NewGuid().ToString('N')+'.json')
$dumpFile=Join-Path $runtimeDir ('backup-work-'+[Guid]::NewGuid().ToString('N')+'.sql')
function Invoke-BackupSql([string]$statement){$result=$statement | & (Join-Path $mysqlBin 'mysql.exe') --no-defaults --host=127.0.0.1 "--port=$($config.dbPort)" --user=root --batch --skip-column-names;if($LASTEXITCODE -ne 0){throw 'Database command failed.'};return ($result -join "`n").Trim()}
try {
 $env:MYSQL_PWD=$config.rootPassword
 if($VerifyFile){
  & node scripts/backup-crypto.cjs unpack $VerifyFile $temporary
  if($LASTEXITCODE -ne 0){throw 'Backup authentication/decryption failed; nothing restored.'}
  $bundle=Get-Content -LiteralPath $temporary -Raw -Encoding UTF8 | ConvertFrom-Json
  if($bundle.format -ne 1){throw 'Unsupported backup payload.'}
  foreach($file in $bundle.files){$hash=[Security.Cryptography.SHA256]::Create();try{$digest=([BitConverter]::ToString($hash.ComputeHash([Convert]::FromBase64String($file.data)))).Replace('-','').ToLowerInvariant();if($digest -ne $file.sha256){throw 'Asset integrity mismatch.'}}finally{$hash.Dispose()}}
  $restoredSchema='eos_restore_'+[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
  $started=Get-Date
  $null=Invoke-BackupSql "CREATE DATABASE $restoredSchema CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
  $bundle.dump | & (Join-Path $mysqlBin 'mysql.exe') --no-defaults --host=127.0.0.1 "--port=$($config.dbPort)" --user=root $restoredSchema
  if($LASTEXITCODE -ne 0){throw "Restore failed. Isolated schema retained: $restoredSchema"}
  $counts=@{}
  foreach($property in $bundle.counts.PSObject.Properties){$table=$property.Name;if($table -notmatch '^[a-zA-Z0-9_]+$'){throw 'Invalid table identifier.'};$actual=[long](Invoke-BackupSql "SELECT COUNT(*) FROM $restoredSchema.$table;");if($actual -ne [long]$property.Value){throw "Row count mismatch for $table"};$counts[$table]=$actual}
  $evidence=[ordered]@{verifiedAt=[DateTime]::UtcNow.ToString('o');backupCreatedAt=$bundle.createdAt;restoredSchema=$restoredSchema;durationMillis=[int]((Get-Date)-$started).TotalMilliseconds;tablesVerified=$counts.Count;filesVerified=@($bundle.files).Count;remoteDocumentVersions=$bundle.remoteDocumentVersions;counts=$counts;scope='Isolated database import and local asset hash verification; remote object bytes are excluded; no live records or files replaced'}
  $evidencePath=Join-Path $runtimeDir ('restore-'+$restoredSchema+'.json');$evidence | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $evidencePath -Encoding UTF8
  Write-Host "Restore verified: $evidencePath";Write-Host "Isolated review schema retained: $restoredSchema";return
 }
 & (Join-Path $PSScriptRoot 'scripts/LocalRuntime.ps1') -Action Pause
 $tables=(Invoke-BackupSql 'SHOW TABLES FROM enterpriseos;') -split "`n";$counts=@{}
 foreach($table in $tables){$table=$table.Trim();if($table -notmatch '^[a-zA-Z0-9_]+$'){throw 'Invalid table identifier.'};$counts[$table]=[long](Invoke-BackupSql "SELECT COUNT(*) FROM enterpriseos.$table;")}
 & (Join-Path $mysqlBin 'mysqldump.exe') --no-defaults --host=127.0.0.1 "--port=$($config.dbPort)" --user=root --single-transaction --hex-blob --set-gtid-purged=OFF --no-tablespaces --skip-comments "--result-file=$dumpFile" enterpriseos
 if($LASTEXITCODE -ne 0){throw 'Database dump failed.'}
 if((Get-Item -LiteralPath $dumpFile).Length -gt 512MB){throw 'Local backup exceeds 512 MiB. Use managed streaming backup.'}
 $assets=@()
 foreach($folder in @('logos','documents')){$directory=Join-Path $runtimeDir $folder;if(Test-Path -LiteralPath $directory){foreach($file in (Get-ChildItem -LiteralPath $directory -File -Recurse)){if($file.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Backup does not follow symbolic links.'};$bytes=[IO.File]::ReadAllBytes($file.FullName);$assets+=@{name=$folder+'/'+$file.Name;sha256=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant();data=[Convert]::ToBase64String($bytes)}}}
 }
 $remoteVersions=0
 $hasBackend=[int](Invoke-BackupSql "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='enterpriseos' AND table_name='document_versions' AND column_name='storage_backend';")
 if($hasBackend){$remoteVersions=[long](Invoke-BackupSql "SELECT COUNT(*) FROM enterpriseos.document_versions WHERE storage_backend='S3';")}
 if($remoteVersions -gt 0){Write-Warning "$remoteVersions document versions are in remote object storage. This archive includes metadata only for them. Bucket backup/versioning and separate remote restore verification are required."}
  $bundle=@{format=1;createdAt=[DateTime]::UtcNow.ToString('o');applicationEncryptionKey=$config.encryptionKey;remoteDocumentVersions=$remoteVersions;counts=$counts;dump=[IO.File]::ReadAllText($dumpFile);files=$assets}
 $bundle | ConvertTo-Json -Depth 8 -Compress | Set-Content -LiteralPath $temporary -Encoding UTF8
 $backupDir=Join-Path $PSScriptRoot 'var/backups';New-Item -ItemType Directory -Path $backupDir -Force | Out-Null
 $output=Join-Path $backupDir ([DateTime]::UtcNow.ToString('yyyy-MM-ddTHH-mm-ss')+'.eosbackup')
 & node scripts/backup-crypto.cjs pack $temporary $output
 if($LASTEXITCODE -ne 0){throw 'Backup encryption failed.'}
 Write-Host "Encrypted backup created: $output";Write-Host 'Verify with .\Backup-EnterpriseOS.ps1 -VerifyFile <path>. Restart with .\Start-EnterpriseOS.ps1.'
} finally {
 foreach($file in @($temporary,$dumpFile)){if(Test-Path -LiteralPath $file){Remove-Item -LiteralPath $file}}
 $env:MYSQL_PWD=$previousPassword;$OutputEncoding=$previousEncoding
 if($createdPassphrase){Remove-Item Env:EOS_BACKUP_PASSPHRASE -ErrorAction SilentlyContinue}
}
