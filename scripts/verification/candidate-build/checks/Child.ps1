#Requires -Version 7.4
# Short-lived NON-PRODUCT child, used only by the independent OwnedBuildJob check.
param([ValidateSet('Normal','Timeout','Descendant')][string]$Mode,[string]$Root)
$ErrorActionPreference='Stop'
switch($Mode){
    'Normal' { exit 0 }
    'Timeout' { [Threading.Thread]::Sleep(15000);exit 0 }
    'Descendant' {
        $start=[Diagnostics.ProcessStartInfo]::new([Diagnostics.Process]::GetCurrentProcess().MainModule.FileName)
        $start.UseShellExecute=$false;$start.CreateNoWindow=$true;$start.WorkingDirectory=$Root
        foreach($arg in @('-NoLogo','-NoProfile','-NonInteractive','-File',$PSCommandPath,'-Mode','Timeout','-Root',$Root)){$start.ArgumentList.Add($arg)}
        $child=[Diagnostics.Process]::Start($start)
        try{[IO.File]::WriteAllText((Join-Path $Root 'owned-descendant.json'),('{"pid":'+$child.Id+'}'))}finally{$child.Dispose()}
        exit 0
    }
}
