$apk = "D:\document\project\hongguoTV\kotlin-tv\app\build\outputs\apk\release\app-release.apk"
$fileBytes = [System.IO.File]::ReadAllBytes($apk)
$boundary = [System.Guid]::NewGuid().ToString("N")
$crlf = "`r`n"
$header = "--$boundary" + $crlf + "Content-Disposition: form-data; name=`"file`"; filename=`"app-release.apk`"" + $crlf + "Content-Type: application/octet-stream" + $crlf + $crlf
$footer = $crlf + "--$boundary--" + $crlf
$headerBytes = [System.Text.Encoding]::UTF8.GetBytes($header)
$footerBytes = [System.Text.Encoding]::UTF8.GetBytes($footer)
$bodyBytes = New-Object byte[] ($headerBytes.Length + $fileBytes.Length + $footerBytes.Length)
[Array]::Copy($headerBytes, 0, $bodyBytes, 0, $headerBytes.Length)
[Array]::Copy($fileBytes, 0, $bodyBytes, $headerBytes.Length, $fileBytes.Length)
[Array]::Copy($footerBytes, 0, $bodyBytes, $headerBytes.Length + $fileBytes.Length, $footerBytes.Length)
$r = Invoke-WebRequest -Uri "http://192.168.1.26:10000/api/upload" -Method POST -ContentType "multipart/form-data; boundary=$boundary" -Body $bodyBytes -UseBasicParsing
$r.Content | ConvertFrom-Json | ConvertTo-Json -Compress
