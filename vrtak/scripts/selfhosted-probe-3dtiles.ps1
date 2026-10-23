<#
Probes the Self-Hosted server's 3D Tiles assets (endpoint and tileset.json),
to find which have finished processing: Office_Park (3), the OpenDroneMap
mesh (13), Delfshaven (14, 15), the box re-upload (18), the Gaussian splat
(19), the 2022 LiDAR (20) and the elevated box (21).
#>
& "$PSScriptRoot\selfhosted-probe.ps1" -Assets '3 13 14 15 18 19 20 21'
